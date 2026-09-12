{
  description = "Vanadium Java 21 development and headless testing";

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
              jdk21
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
            JAVA_HOME = "${pkgs.jdk21}";
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
