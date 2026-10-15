# Built from the repository root: see docker-compose.yml.
FROM oven/bun:1.4.2
WORKDIR /app
COPY web/package.json web/bun.lock ./
RUN bun install --frozen-lockfile
COPY web/ ./
RUN bun run build
# vite preview serves the build through the same proxies as the dev server, aimed by the environment.
ENTRYPOINT ["bun", "run", "preview", "--host", "0.0.0.0", "--port", "5173", "--strictPort"]
