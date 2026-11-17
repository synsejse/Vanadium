{ pkgs }:

pkgs.mkShellNoCC {
  packages = with pkgs; [
    jdk25
    bash
    git
    ripgrep
    curl
    jq
    unzip
    zip
    nixfmt
  ];

  JAVA_HOME = "${pkgs.jdk25}";

  # LWJGL/SDL load display and audio libraries dynamically, outside their ELF dependencies.
  shellHook = pkgs.lib.optionalString pkgs.stdenv.isLinux ''
    export LD_LIBRARY_PATH="${
      pkgs.lib.makeLibraryPath (
        with pkgs;
        [
          stdenv.cc.cc.lib
          vulkan-loader
          libglvnd
          wayland
          libxkbcommon
          libx11
          libxcursor
          libxrandr
          libxi
          libxfixes
          libxext
          libxrender
          alsa-lib
          libpulseaudio
          udev
        ]
      )
    }''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  '';
}
