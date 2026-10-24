FROM node:22-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
COPY relay.mjs protocol.mjs accounts.mjs ./
USER node
EXPOSE 8443
CMD ["node", "relay.mjs"]
