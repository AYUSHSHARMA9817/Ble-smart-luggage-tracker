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

#define TAG "BLE_TRACKER"

// --- Configuration ---
#define DEVICE_ID 0x0000AB01 // Random default per company backend
#define MANUFACTURER_ID 0xFF01

#define PIN_REED_SWITCH GPIO_NUM_5
// One side of the reed switch is expected to be connected to GND.
// Most bare 2-pin glass reed switches are normally-open (NO):
// magnet near -> switch closes -> raw LOW with the internal pull-up enabled.
// Set to 1 only if your specific reed switch is normally-closed (NC).
#define REED_SWITCH_IS_NC 0
#define REED_SWITCH_CLOSED_LEVEL ((REED_SWITCH_IS_NC) ? 1 : 0)
#define REED_SWITCH_TYPE_STR ((REED_SWITCH_IS_NC) ? "NC" : "NO")
// #define PIN_ADC_BATTERY ADC_CHANNEL_4 // GPIO4 is ADC1_CH4

#define HEARTBEAT_INTERVAL_SEC 6
#define SELF_TEST_INTERVAL_SEC 86400 // 24 hours

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

// Health Status Bits
#define HEALTH_BIT_REED_FAULT (1 << 0)
#define HEALTH_BIT_BOOT_CONTRADICT (1 << 1)
#define HEALTH_BIT_ADC_FAULT (1 << 2)

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

static int read_reed_raw_level() { return gpio_get_level(PIN_REED_SWITCH); }

static uint8_t read_bag_state() {
  int val = read_reed_raw_level();
  return val == REED_SWITCH_CLOSED_LEVEL ? STATE_CLOSED : STATE_OPEN;
}

static void log_reed_state(const char *context) {
  int raw_level = read_reed_raw_level();
  uint8_t bag_state = read_bag_state();
  ESP_LOGI(TAG, "%s: GPIO%d raw=%d -> bag_state=%u (%s contact)", context,
           PIN_REED_SWITCH, raw_level, bag_state, REED_SWITCH_TYPE_STR);
}

// Read Battery Level via ADC
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

static int ble_gap_event(struct ble_gap_event *event, void *arg) { return 0; }

static void update_advertising_payload(uint8_t packet_type) {
  struct ble_hs_adv_fields adv_fields;
  memset(&adv_fields, 0, sizeof(adv_fields));

  adv_fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;

  tracker_payload_t p;
  p.device_id = DEVICE_ID;
  p.bag_state = read_bag_state();
  p.battery_level = BATT_GOOD; // read_battery_level();
  p.seq_num = g_sequence_number++;
  p.packet_type = packet_type;
  p.health_status = (packet_type == PKT_SELF_TEST) ? g_health_status : 0;
  p.days_since_change =
      (packet_type == PKT_SELF_TEST) ? g_days_since_change : 0;

  static uint8_t mfg_data[32];
  uint16_t mfr_id = MANUFACTURER_ID;
  mfg_data[0] = mfr_id & 0xFF;
  mfg_data[1] = (mfr_id >> 8) & 0xFF;

  memcpy(&mfg_data[2], &p, sizeof(p));

  adv_fields.mfg_data = mfg_data;
  adv_fields.mfg_data_len = 2 + sizeof(p);

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

static void send_burst(uint8_t packet_type, int count) {
  for (int i = 0; i < count; i++) {
    update_advertising_payload(packet_type);
    vTaskDelay(pdMS_TO_TICKS(2000)); // 2s spacing
  }
}

// Ensure NVS memory tracks faults appropriately
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

// Reset the inactivity fault counter safely
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

void tracker_task(void *pvParameter) {
  TickType_t last_heartbeat = xTaskGetTickCount();
  TickType_t last_self_test = xTaskGetTickCount();
  uint8_t prev_state = read_bag_state();

  memset(&g_adv_params, 0, sizeof(g_adv_params));
  g_adv_params.conn_mode = BLE_GAP_CONN_MODE_NON;
  g_adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
  g_adv_params.itvl_min = 160; // 100ms
  g_adv_params.itvl_max = 160;

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

    vTaskDelay(pdMS_TO_TICKS(100)); // Poll every 100ms
  }
}

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
}

void ble_app_on_sync(void) {
  xTaskCreate(tracker_task, "tracker_task", 4096, NULL, 5, NULL);
}

void host_task(void *param) { nimble_port_run(); }

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
