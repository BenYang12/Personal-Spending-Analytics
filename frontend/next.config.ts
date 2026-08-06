import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emits .next/standalone with a minimal server.js and only the node_modules
  // actually reached at runtime. The Docker image then carries ~50MB instead of
  // the full dependency tree.
  //
  // Caveat the docs call out explicitly: standalone does NOT copy `public` or
  // `.next/static`, so the Dockerfile copies them by hand. Miss that and the
  // app boots fine while serving no CSS — a confusing failure to debug.
  output: "standalone",
};

export default nextConfig;
