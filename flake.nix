{
  description = "Vanadium Java 25 development and testing";

  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-26.05/nixexprs.tar.xz";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          default = pkgs.mkShellNoCC {
            packages = with pkgs; [
              jdk25
              bash
              git
              ripgrep
              curl
              jq
              unzip
              zip
              python3
              shellcheck
              nixfmt
            ];
            JAVA_HOME = "${pkgs.jdk25}";
            # LWJGL loads graphics APIs dynamically when starting the client.
            shellHook = pkgs.lib.optionalString pkgs.stdenv.isLinux ''
              export LD_LIBRARY_PATH="${
                pkgs.lib.makeLibraryPath [
                  pkgs.vulkan-loader
                  pkgs.libglvnd
                ]
              }''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
            '';
          };
        }
      );

      checks = forAllSystems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          development-files =
            pkgs.runCommand "vanadium-development-files"
              {
                nativeBuildInputs = [
                  pkgs.shellcheck
                  pkgs.python3
                  pkgs.nixfmt
                ];
              }
              ''
                shellcheck ${./scripts/bench-server.sh}
                python3 -c 'import pathlib; p = pathlib.Path("${./scripts/dev-server.py}"); compile(p.read_text(), str(p), "exec")'
                python3 -m json.tool ${./scripts/runtime-mods.json} > /dev/null
                nixfmt --check ${./flake.nix}
                touch "$out"
              '';
        }
      );

      formatter = forAllSystems (system: nixpkgs.legacyPackages.${system}.nixfmt);
    };
}
