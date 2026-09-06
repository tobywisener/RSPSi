# RSPSi

## Development Shell (`shell.nix`)

This project includes a `shell.nix` that provides a reproducible Java/Gradle environment.

### Enter the shell

```bash
cd /Users/toby/elvarg-web/RSPSi
nix-shell
```

When the shell starts it will:
- set `JAVA_HOME` to JDK 17
- configure `GRADLE_OPTS` to use that JDK
- ensure `./gradlew` is executable
- define `rspsi-run` alias

### Common commands (inside `nix-shell`)

Run the editor:

```bash
rspsi-run
```

Compile client module:

```bash
gradle --no-daemon :Client:compileJava
```

Compile editor module:

```bash
gradle --no-daemon :Editor:compileJava
```

Run tests in cache library:

```bash
gradle --no-daemon :RS-Cache-Library:test
```

### One-liner usage

If you do not want an interactive shell:

```bash
nix-shell --run "gradle --no-daemon :Client:compileJava"
```
