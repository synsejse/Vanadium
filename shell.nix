{
  system ? builtins.currentSystem,
}:

let
  # Share the flake's pinned nixpkgs without requiring flake support or a NIX_PATH channel.
  locked = (builtins.fromJSON (builtins.readFile ./flake.lock)).nodes.nixpkgs.locked;
  source = builtins.fetchTarball {
    url = locked.url;
    sha256 = locked.narHash;
  };
  pkgs = import source { inherit system; };
in
import ./nix/shell.nix { inherit pkgs; }
