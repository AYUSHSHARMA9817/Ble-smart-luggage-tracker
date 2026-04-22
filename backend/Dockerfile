FROM node:22-alpine

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

COPY . .

ENV PORT=8787
ENV DATA_PATH=/data/app.json

EXPOSE 8787

CMD ["npm", "start"]
