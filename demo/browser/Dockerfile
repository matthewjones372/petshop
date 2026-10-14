# The image's browsers match one Playwright release, so the package below is pinned to the same version.
FROM mcr.microsoft.com/playwright:v1.63.0-noble
WORKDIR /app
RUN npm install --no-save --no-audit --no-fund playwright@1.63.0
COPY demo/browser/visit.mjs ./
ENTRYPOINT ["node", "visit.mjs"]
