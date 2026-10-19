# A users API for the prod target that, unlike JSONPlaceholder, keeps what you write.
# Build: docker build -t gitops-users-api server/
# Run:   docker run -p 3000:3000 -e API_TOKEN=... -v gitops-users:/data gitops-users-api
FROM node:20-alpine
WORKDIR /app
RUN npm install --omit=dev --no-fund --no-audit json-server@0.17.4
COPY auth.js start.sh seed.json ./
RUN mkdir /data && chown node:node /data
ENV DATA_FILE=/data/db.json PORT=3000
VOLUME /data
EXPOSE 3000
USER node
CMD ["sh", "/app/start.sh"]
