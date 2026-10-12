import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  agentRules: false,
  output: 'standalone', 
  transpilePackages: ["@intelligent-recruitment/display-dictionary"],
};

export default nextConfig;
