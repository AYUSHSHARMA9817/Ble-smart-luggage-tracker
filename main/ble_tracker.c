/**
 * @file ble_tracker.c
 * @brief BLE smart luggage tracker firmware for ESP32.
 *
 * Broadcasts a compact 10-byte manufacturer-specific BLE advertisement
 * containing the bag's reed-switch state, external power-health level, sequence number,
 * packet type, health status, and inactivity day counter.
 *
 * Three packet types are supported:
 *   - HEARTBEAT    : periodic keepalive (every HEARTBEAT_INTERVAL_SEC seconds)
 *   - STATE_CHANGE : burst of 5 packets on reed-switch transition
 *   - SELF_TEST    : daily health report with fault bits and inactivity counter
 *
 * NVS (non-volatile storage) persists the last-known bag state and inactivity
 * day counter across resets so boot-time contradiction faults can be detected.
 */

#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
// #include "esp_adc/adc_oneshot.h"
#include "esp_bt.h"
#include "esp_log.h"
#include "nvs.h"
#include "nvs_flash.h"

#include "host/ble_hs.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "sensor_manager.h"

#define TAG "BLE_TRACKER"

// --- Configuration ---
#define DEVICE_ID 0x0000AB01 // Random default per company backend
#define MANUFACTURER_ID 0xFF01

#define PIN_REED_SWITCH GPIO_NUM_5
// One side of the reed switch is expected to be connected to GND.
// Bare 2-pin glass reed switches are normally-open (NO):
// magnet near -> switch closes -> raw LOW with the internal pull-up enabled.
// Set to 1 only if your specific reed switch is normally-closed (NC).
#define REED_SWITCH_IS_NC 0
#define REED_SWITCH_CLOSED_LEVEL ((REED_SWITCH_IS_NC) ? 1 : 0)
#define REED_SWITCH_TYPE_STR ((REED_SWITCH_IS_NC) ? "NC" : "NO")
// #define PIN_ADC_BATTERY ADC_CHANNEL_4 // GPIO4 is ADC1_CH4

#define HEARTBEAT_INTERVAL_SEC 6
#define SELF_TEST_INTERVAL_SEC 86400 // 24 hours
#define SENSOR_TELEMETRY_INTERVAL_SEC 30
#define SENSOR_PROBE_INTERVAL_SEC 60

// --- Packet Values ---
#define STATE_CLOSED 0x00
#define STATE_OPEN 0x01

#define BATT_CRITICAL 0x00 // < 2.4V
#define BATT_LOW 0x01      // 2.4 - 2.6V
#define BATT_MEDIUM 0x02   // 2.6 - 2.8V
#define BATT_GOOD 0x03     // > 2.8V

#define PKT_HEARTBEAT 0x00
#define PKT_STATE_CHANGE 0x01
#define PKT_SELF_TEST 0x02
#define PKT_SENSOR_TLV 0x03

// Health Status Bits
#define HEALTH_BIT_REED_FAULT (1 << 0)
#define HEALTH_BIT_BOOT_CONTRADICT (1 << 1)
#define HEALTH_BIT_ADC_FAULT (1 << 2)

// Advertising payload limits: BLE legacy advertising payload is 31 bytes.
// If we only advertise FLAGS + Manufacturer Specific Data, the manufacturer
// data block can be at most 26 bytes.
#define MFG_DATA_MAX_LEN 26
#define SENSOR_TLV_MAX_LEN (MFG_DATA_MAX_LEN - 2 - sizeof(tracker_payload_t))

// Strict 10-Byte Manufacturer Payload
#pragma pack(push, 1)
typedef struct {
  uint32_t device_id;
  uint8_t bag_state;
  uint8_t battery_level;
  uint8_t seq_num;
  uint8_t packet_type;
  uint8_t health_status;
  uint8_t days_since_change;
} tracker_payload_t;
#pragma pack(pop)

// Global State
static uint8_t g_sequence_number = 0;
static uint8_t g_health_status = 0;
static uint8_t g_days_since_change = 0;
// static adc_oneshot_unit_handle_t adc1_handle;
static struct ble_gap_adv_params g_adv_params;
static bool g_adv_started = false;

/**
 * @brief Read the raw GPIO level of the reed switch pin.
 * @return 0 (LOW) or 1 (HIGH).
 */
static int read_reed_raw_level() { return gpio_get_level(PIN_REED_SWITCH); }

/**
 * @brief Translate the raw GPIO level to a logical bag state.
 *
 * Accounts for the REED_SWITCH_IS_NC compile-time option so that both
 * normally-open and normally-closed switches report the same STATE_CLOSED /
 * STATE_OPEN values to the rest of the firmware.
 *
 * @return STATE_CLOSED (0x00) or STATE_OPEN (0x01).
 */
static uint8_t read_bag_state() {
  int val = read_reed_raw_level();
  return val == REED_SWITCH_CLOSED_LEVEL ? STATE_CLOSED : STATE_OPEN;
}

/**
 * @brief Log the current reed-switch raw level and derived bag state.
 * @param context  Caller-supplied label included in the log message.
 */
static void log_reed_state(const char *context) {
  int raw_level = read_reed_raw_level();
  uint8_t bag_state = read_bag_state();
  ESP_LOGI(TAG, "%s: GPIO%d raw=%d -> bag_state=%u (%s contact)", context,
           PIN_REED_SWITCH, raw_level, bag_state, REED_SWITCH_TYPE_STR);
}

// Read external power level via ADC
/*
static uint8_t read_battery_level() {
  int raw_val;
  esp_err_t err = adc_oneshot_read(adc1_handle, PIN_ADC_BATTERY, &raw_val);
  if (err != ESP_OK) {
    g_health_status |= HEALTH_BIT_ADC_FAULT;
    return BATT_CRITICAL;
  }
  g_health_status &= ~(HEALTH_BIT_ADC_FAULT);

  // NOTE: This assumes a simple voltage divider logic or raw 3.3V connection.
  // Usually on ESP32-C3, 11dB attenuation measures up to ~2500mV.
  // You may need a voltage divider and multiply the result here by the divider
  // ratio.
  float voltage = (raw_val / 4095.0) * 3.3; // Simplified approximation

  if (voltage >= 2.8)
    return BATT_GOOD;
  if (voltage >= 2.6)
    return BATT_MEDIUM;
  if (voltage >= 2.4)
    return BATT_LOW;
  return BATT_CRITICAL;
}
*/

/**
 * @brief NimBLE GAP event callback (required by the API).
 *
 * The device operates as a non-connectable advertiser, so no GAP events
 * require handling. The callback is registered to satisfy the NimBLE API
 * contract and always returns 0.
 */
static int ble_gap_event(struct ble_gap_event *event, void *arg) { return 0; }

/**
 * @brief Build and broadcast a BLE advertisement with the current tracker state.
 *
 * Fills a 10-byte manufacturer-specific payload, stops any running
 * advertisement, updates the advertisement fields, and restarts advertising.
 * The static mfg_data buffer is intentional: the NimBLE stack may reference
 * the buffer asynchronously, so it must not be on the stack.
 *
 * health_status and days_since_change are only populated in SELF_TEST packets;
 * they are zeroed in HEARTBEAT and STATE_CHANGE packets to keep those small.
 *
 * @param packet_type  One of PKT_HEARTBEAT, PKT_STATE_CHANGE, PKT_SELF_TEST.
 */
static void update_advertising_payload(uint8_t packet_type) {
  struct ble_hs_adv_fields adv_fields;
  memset(&adv_fields, 0, sizeof(adv_fields));

  adv_fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
  // Enable TX power level in advertising payload to allow the receiver to calculate distance via RSSI
  // This satisfies the baseline requirement for directional location/proximity tracking.
  // Note: True CTE (Constant Tone Extension) for AoA/AoD requires antenna arrays not found on standard phones.
  adv_fields.tx_pwr_lvl_is_present = 1;

  tracker_payload_t p;
  p.device_id = DEVICE_ID;
  p.bag_state = read_bag_state();
  // Current production hardware is powered by a USB power bank. Until
  // voltage sensing is wired and calibrated, advertise a stable external
  // power state rather than a coin-cell charge bucket.
  p.battery_level = BATT_GOOD; // read_battery_level();
  p.seq_num = g_sequence_number++;
  p.packet_type = packet_type;
  p.health_status = (packet_type == PKT_SELF_TEST) ? g_health_status : 0;
  p.days_since_change =
      (packet_type == PKT_SELF_TEST) ? g_days_since_change : 0;

  // Static buffer: must outlive the ble_gap_adv_set_fields call.
  static uint8_t mfg_data[MFG_DATA_MAX_LEN];
  uint16_t mfr_id = MANUFACTURER_ID;
  mfg_data[0] = mfr_id & 0xFF;         // Manufacturer ID low byte (little-endian)
  mfg_data[1] = (mfr_id >> 8) & 0xFF;  // Manufacturer ID high byte

  memcpy(&mfg_data[2], &p, sizeof(p));

  adv_fields.mfg_data = mfg_data;
  adv_fields.mfg_data_len = 2 + sizeof(p);

  if (packet_type == PKT_SENSOR_TLV) {
    size_t tlv_len = sensor_manager_encode_tlv(
        &mfg_data[2 + sizeof(p)], SENSOR_TLV_MAX_LEN, NULL);
    adv_fields.mfg_data_len = 2 + sizeof(p) + tlv_len;
  }

  if (g_adv_started) {
    int stop_rc = ble_gap_adv_stop();
    if (stop_rc != 0 && stop_rc != BLE_HS_EALREADY) {
      ESP_LOGW(TAG, "ble_gap_adv_stop failed: %d", stop_rc);
    }
    g_adv_started = false;
  }

  int set_rc = ble_gap_adv_set_fields(&adv_fields);
  if (set_rc != 0) {
    ESP_LOGE(TAG, "ble_gap_adv_set_fields failed: %d", set_rc);
    return;
  }

  int start_rc = ble_gap_adv_start(0, NULL, BLE_HS_FOREVER, &g_adv_params,
                                   ble_gap_event, NULL);
  if (start_rc != 0) {
    ESP_LOGE(TAG, "ble_gap_adv_start failed: %d", start_rc);
    return;
  }

  g_adv_started = true;
  ESP_LOGI(TAG,
           "Sent ADV [Type: 0x%02X, Seq: %d, State: %d, Raw: %d, Batt: %d]",
           packet_type, p.seq_num, p.bag_state, read_reed_raw_level(),
           p.battery_level);
}

/**
 * @brief Broadcast @p count advertisements of the given packet type with
 *        a 2-second gap between each, to improve reliable reception.
 *
 * @param packet_type  Packet type for all advertisements in the burst.
 * @param count        Number of advertisements to send.
 */
static void send_burst(uint8_t packet_type, int count) {
  for (int i = 0; i < count; i++) {
    update_advertising_payload(packet_type);
    vTaskDelay(pdMS_TO_TICKS(2000)); // 2s spacing
  }
}

/**
 * @brief On boot, load persisted health state from NVS and flag any faults.
 *
 * Two fault conditions are checked:
 *   1. Boot contradiction: the bag state at boot differs from the last
 *      persisted state, implying a state change occurred while unpowered.
 *      Sets HEALTH_BIT_BOOT_CONTRADICT and updates NVS.
 *   2. Reed inactivity: the bag has not changed state in >= 30 days.
 *      Sets HEALTH_BIT_REED_FAULT.
 *
 * Missing NVS keys (first boot) default to STATE_CLOSED and 0 days, which
 * is the normal no-fault state and requires no special handling.
 */
static void init_nvs_health_checks() {
  nvs_handle_t nvs_handle;
  esp_err_t err = nvs_open("tracker", NVS_READWRITE, &nvs_handle);
  if (err != ESP_OK)
    return;

  uint8_t saved_state = STATE_CLOSED;
  nvs_get_u8(nvs_handle, "last_state", &saved_state);
  nvs_get_u8(nvs_handle, "days", &g_days_since_change);

  uint8_t current_state = read_bag_state();

  // Approach 1: Boot Contradiction Fault Detection
  if (saved_state != current_state) {
    g_health_status |= HEALTH_BIT_BOOT_CONTRADICT;
    ESP_LOGW(TAG, "Boot contradiction! Saved: %d, Current: %d", saved_state,
             current_state);
    nvs_set_u8(nvs_handle, "last_state", current_state);
    nvs_commit(nvs_handle);
  }

  // Approach 2: Switch Inactivity Fault Detection (30 days without change)
  if (g_days_since_change >= 30) {
    g_health_status |= HEALTH_BIT_REED_FAULT;
  }

  nvs_close(nvs_handle);
}

/**
 * @brief Persist the new bag state to NVS and clear the inactivity fault.
 *
 * Called whenever a confirmed state change is detected. Resets the
 * days-since-change counter to 0 and clears HEALTH_BIT_REED_FAULT so the
 * next SELF_TEST reports a healthy switch.
 */
static void track_state_change() {
  nvs_handle_t nvs_handle;
  if (nvs_open("tracker", NVS_READWRITE, &nvs_handle) == ESP_OK) {
    nvs_set_u8(nvs_handle, "last_state", read_bag_state());
    g_days_since_change = 0;
    nvs_set_u8(nvs_handle, "days", 0);
    nvs_commit(nvs_handle);
    nvs_close(nvs_handle);
  }
  g_health_status &= ~(HEALTH_BIT_REED_FAULT);
}

/**
 * @brief Main FreeRTOS task: monitors the reed switch and drives the
 *        heartbeat, state-change, and self-test advertisement cadences.
 *
 * Loop behaviour:
 *   1. Every 100 ms: sample the reed switch with a 50 ms debounce. On a
 *      confirmed state change, send a burst of 5 STATE_CHANGE packets.
 *   2. Every 24 hours: increment the inactivity counter, check for the reed
 *      fault threshold, and send a burst of 5 SELF_TEST packets.
 *   3. Every HEARTBEAT_INTERVAL_SEC seconds: send one HEARTBEAT packet.
 *
 * @param pvParameter  Unused FreeRTOS task parameter.
 */
void tracker_task(void *pvParameter) {
  TickType_t last_heartbeat = xTaskGetTickCount();
  TickType_t last_self_test = xTaskGetTickCount();
  TickType_t last_sensor = xTaskGetTickCount();
  TickType_t last_sensor_probe = xTaskGetTickCount();
  uint8_t prev_state = read_bag_state();

  memset(&g_adv_params, 0, sizeof(g_adv_params));
  g_adv_params.conn_mode = BLE_GAP_CONN_MODE_NON;
  g_adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
  g_adv_params.itvl_min = 160; // 100ms (units of 0.625ms)
  g_adv_params.itvl_max = 160; // 100ms (units of 0.625ms)

  update_advertising_payload(PKT_HEARTBEAT);

  while (1) {
    TickType_t now = xTaskGetTickCount();

    // 1. Debounced physical state change check
    uint8_t current_state = read_bag_state();
    if (current_state != prev_state) {
      vTaskDelay(pdMS_TO_TICKS(50)); // Debounce
      if (read_bag_state() == current_state) {
        ESP_LOGI(TAG, "STATE CHANGE DETECTED: %d -> %d", prev_state,
                 current_state);
        log_reed_state("Debounced reed state");
        track_state_change();
        prev_state = current_state;
        send_burst(PKT_STATE_CHANGE, 5);
        last_heartbeat = xTaskGetTickCount();
        continue;
      }
    }

    // 2. 24h Self Test logic
    if ((now - last_self_test) / configTICK_RATE_HZ >= SELF_TEST_INTERVAL_SEC) {
      ESP_LOGI(TAG, "Triggering 24h SELF_TEST loop");

      // Increment inactivity counter, capped at 255 to avoid uint8 overflow.
      if (g_days_since_change < 255) {
        g_days_since_change++;
        nvs_handle_t nvs_handle;
        if (nvs_open("tracker", NVS_READWRITE, &nvs_handle) == ESP_OK) {
          nvs_set_u8(nvs_handle, "days", g_days_since_change);
          nvs_commit(nvs_handle);
          nvs_close(nvs_handle);
        }
      }
      if (g_days_since_change >= 30)
        g_health_status |= HEALTH_BIT_REED_FAULT;

      send_burst(PKT_SELF_TEST, 5);
      last_self_test = now;
      last_heartbeat = xTaskGetTickCount();
      continue;
    }

    // 3. Heartbeat Loop
    if ((now - last_heartbeat) / configTICK_RATE_HZ >= HEARTBEAT_INTERVAL_SEC) {
      update_advertising_payload(PKT_HEARTBEAT);
      last_heartbeat = now;
    }

    // 4. Sensor telemetry loop (best effort, independent of bag state)
    if ((now - last_sensor) / configTICK_RATE_HZ >= SENSOR_TELEMETRY_INTERVAL_SEC) {
      update_advertising_payload(PKT_SENSOR_TLV);
      last_sensor = now;
    }

    // 5. Sensor re-probe loop to support hot-plug (I2C)
    if ((now - last_sensor_probe) / configTICK_RATE_HZ >= SENSOR_PROBE_INTERVAL_SEC) {
      (void)sensor_manager_probe();
      last_sensor_probe = now;
    }

    vTaskDelay(pdMS_TO_TICKS(100)); // Poll every 100ms
  }
}

/**
 * @brief Initialise hardware peripherals.
 *
 * Configures the reed-switch GPIO as a pull-up input with interrupts
 * disabled (polling is used instead). Also calls init_nvs_health_checks()
 * to restore persisted health state before the BLE stack starts.
 *
 * The ADC1 initialisation for power measurement is disabled pending
 * hardware-specific voltage divider configuration (see commented block).
 */
void hw_init() {
  // Init Reed Switch on GPIO5
  gpio_reset_pin(PIN_REED_SWITCH);

  gpio_config_t io_conf = {
      .intr_type = GPIO_INTR_DISABLE,
      .mode = GPIO_MODE_INPUT,
      .pin_bit_mask = (1ULL << PIN_REED_SWITCH),
      .pull_down_en = GPIO_PULLDOWN_DISABLE,
      .pull_up_en = GPIO_PULLUP_ENABLE,
  };
  ESP_ERROR_CHECK(gpio_config(&io_conf));
  log_reed_state("Reed input configured");

  // Init ADC1 for Battery Measurement on GPIO4
  /*
    adc_oneshot_unit_init_cfg_t init_config1 = {
        .unit_id = ADC_UNIT_1,
    };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init_config1, &adc1_handle));

    adc_oneshot_chan_cfg_t config = {
        .bitwidth = ADC_BITWIDTH_DEFAULT,
        .atten = ADC_ATTEN_DB_12,
    };
    ESP_ERROR_CHECK(
        adc_oneshot_config_channel(adc1_handle, PIN_ADC_BATTERY, &config));
  */

  // Assess NVS health checks on boot
  init_nvs_health_checks();

  // Optional plug-and-play sensors via I2C (best effort).
  (void)sensor_manager_init();
}

/**
 * @brief NimBLE host sync callback – spawns the tracker task once the BLE
 *        stack is ready.
 *
 * Registered as ble_hs_cfg.sync_cb before nimble_port_freertos_init().
 */
void ble_app_on_sync(void) {
  xTaskCreate(tracker_task, "tracker_task", 4096, NULL, 5, NULL);
}

/**
 * @brief FreeRTOS task that runs the NimBLE host event loop.
 *
 * Must be created via nimble_port_freertos_init() which sets the correct
 * stack size and priority for the NimBLE host.
 *
 * @param param  Unused.
 */
void host_task(void *param) { nimble_port_run(); }

/**
 * @brief Application entry point.
 *
 * Initialises NVS (erasing if pages are full or the version changed),
 * calls hw_init() to configure peripherals, then starts the NimBLE host
 * and registers the sync callback that launches tracker_task.
 */
void app_main(void) {
  esp_err_t ret = nvs_flash_init();
  if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
      ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
    ESP_ERROR_CHECK(nvs_flash_erase());
    ret = nvs_flash_init();
  }
  ESP_ERROR_CHECK(ret);

  hw_init();

  nimble_port_init();
  ble_hs_cfg.sync_cb = ble_app_on_sync;
  nimble_port_freertos_init(host_task);
}
