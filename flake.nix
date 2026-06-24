{
  description = "Watchmen Logo Builder";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        jdk = pkgs.jdk25;

        gitName = "Nedarym";
        gitEmail = "66786343+nedarim2005@users.noreply.github.com";
        sshKey = "~/.ssh/id_fabricmod";
        gitConfig = pkgs.writeText "gitconfig" ''
          [user]
              name = ${gitName}
              email = ${gitEmail}
          [core]
              sshCommand = ssh -i ${sshKey} -o IdentitiesOnly=yes
          [init]
              defaultBranch = main
          # [commit]
          #     gpgSign = true
        '';

        gradle = pkgs.gradle.override {
          java = jdk;
          javaToolchains = [ jdk ];
        };

        runtimeLibs = with pkgs; [
          glfw
          openal
          libGL
          vulkan-loader
          xorg.libX11
          xorg.libXcursor
          xorg.libXrandr
          xorg.libXxf86vm
          xorg.libXi
          xorg.libXext
          xorg.libXrender
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.git
            gradle
            jdk
          ];

          JAVA_HOME = "${jdk}";

          GIT_CONFIG_GLOBAL = "${gitConfig}";

          LD_LIBRARY_PATH =
            pkgs.lib.optionalString pkgs.stdenv.isLinux
              (pkgs.lib.makeLibraryPath runtimeLibs);
        };
      });
}
