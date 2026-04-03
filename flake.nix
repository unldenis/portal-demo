{
  description = "Rust development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    portal-lib.url = "github:PortalTechnologiesInc/lib";
  };

  outputs = { self, nixpkgs, flake-utils, portal-lib, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        overlays = [ portal-lib.overlays.default ];
        pkgs = import nixpkgs {
          inherit system overlays;
        };
      in
      {
        packages = rec {
          frontend = pkgs.buildNpmPackage {
            pname = "portal-demo-frontend";
            version = "0.1.0";
            src = ./frontend;

            npmDepsHash = "sha256-AphYurewecYmfqMgOwrdSgqK0AcQ+tnYFNDPXtWILnM=";
            npmInstallFlags = [ "--legacy-peer-deps" ];

            VITE_BACKEND_API_WS = "/ws";

            installPhase = ''
              runHook preInstall
              mkdir -p $out
              cp -r dist/. $out/
              runHook postInstall
            '';
          };

          backend = pkgs.maven.buildMavenPackage {
            pname = "portal-demo-backend";
            version = "1.0-SNAPSHOT";
            src = ./.;

            mvnHash = "sha256-4yT1zhqQXT1UFU/ztgCQ0nA+yDDvbphPqSxGmk/QSls=";
            nativeBuildInputs = [ pkgs.makeWrapper ];

            preBuild = ''
              cp -r ${frontend}/* ./src/main/resources/static
            '';

            installPhase = ''
              runHook preInstall
              install -Dm444 target/portal-demo-1.0-SNAPSHOT.jar \
                $out/lib/portal-demo-backend.jar
              makeWrapper ${pkgs.jre_headless}/bin/java $out/bin/portal-backend \
                --add-flags "-jar $out/lib/portal-demo-backend.jar"
              runHook postInstall
            '';

            meta.mainProgram = "portal-backend";
          };
        };
      }
    ) // {
        overlays.default = final: prev: {
          portal-demo-backend = self.packages.${prev.stdenv.hostPlatform.system}.backend;
        };

        nixosModules = { 
          portal-demo-backend = ./module.nix;
          default = { ... }: {
            imports = [ self.nixosModules.portal-demo-backend ];
            nixpkgs.overlays = [ self.overlays.default ];
          };
        };
    };
}
