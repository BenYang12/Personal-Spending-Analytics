# Multi-stage build using Next's standalone output.

FROM node:22-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
# `npm ci` installs exactly the lockfile — a build must not silently pick up a
# newer transitive dependency than the one that was tested.
RUN npm ci

FROM node:22-slim AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Build-time placeholders. These are NOT the runtime values — the real ones are
# injected by docker-compose when the container starts. They exist only because
# the build imports lib/api.ts, which reads them at module load. Nothing in the
# build calls the API (the dashboard route is dynamic, rendered per request).
ENV LEDGERLENS_API_URL=http://placeholder \
    LEDGERLENS_API_KEY=build-time-only \
    NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1

RUN useradd --create-home nextjs
USER nextjs

# The standalone server plus its traced dependencies.
COPY --from=build --chown=nextjs:nextjs /app/.next/standalone ./
# Standalone deliberately excludes .next/static, so it's copied by hand. Without
# it the app boots and serves unstyled HTML — a genuinely confusing failure.
#
# There is no `public` COPY because there is no public/ directory: the scaffold's
# five unused SVGs were deleted and the favicon lives in app/, where Next's
# file-convention handling picks it up. Leaving the COPY in would fail the build.
COPY --from=build --chown=nextjs:nextjs /app/.next/static ./.next/static

EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0

CMD ["node", "server.js"]
