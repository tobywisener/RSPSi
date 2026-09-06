{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = with pkgs; [
    jdk17
    git
  ];

  shellHook = "
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which javac))))
    export PATH=$JAVA_HOME/bin:$PATH
    export GRADLE_OPTS=\"-Dorg.gradle.java.home=$JAVA_HOME \${GRADLE_OPTS:-}\"
    chmod +x ./gradlew 2>/dev/null || true
    alias rspsi-run='./gradlew --no-daemon :Editor:run --console=plain'
    echo \"RSPSi nix-shell ready\"
    echo \"Java:   $(java -version 2>&1 | head -n 1)\"
    echo \"Gradle: $(./gradlew --version | grep -m1 Gradle)\"
    echo \"Run:    rspsi-run\"
  ";
}
