#!/usr/bin/env bash
# Checks this machine for what the project needs and says what, if anything, to install.
# Run from the repository: ./scripts/doctor.sh
set -u

ok=0; warn=0; fail=0
pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; ok=$((ok + 1)); }
note() { printf '  \033[33mnote\033[0m  %s\n' "$1"; warn=$((warn + 1)); }
miss() { printf '  \033[31mfix\033[0m   %s\n' "$1"; fail=$((fail + 1)); }
has() { command -v "$1" >/dev/null 2>&1; }

# Prints the major version of a java binary, e.g. 21 (1.8 counts as 8).
java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9.]*\).*/\1/p' | head -1 \
    | awk -F. '{ print ($1 == 1) ? $2 : $1 }'
}

echo "Checking what gitops needs on this machine..."
echo

echo "Required"
if has git; then pass "git $(git --version | awk '{print $3}')"; else miss "git: install it from https://git-scm.com"; fi

# ./gradlew runs on JAVA_HOME when it's set, otherwise on the java found on PATH.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"; java_from="JAVA_HOME ($JAVA_HOME)"
elif has java; then
  java_bin="$(command -v java)"; java_from="PATH ($java_bin)"
else
  java_bin=""
fi
if [ -z "$java_bin" ]; then
  miss "Java: none found. Install JDK 21 from https://adoptium.net"
else
  major="$(java_major "$java_bin")"
  if [ -z "$major" ]; then
    note "Java found in $java_from, but its version couldn't be read"
  elif [ "$major" -ge 21 ]; then
    pass "Java $major, from $java_from"
  elif [ "$major" -ge 17 ]; then
    note "Java $major, from $java_from: enough to start Gradle, which downloads JDK 21 for the build on the first run"
  else
    miss "Java $major, from $java_from: Gradle needs Java 17 or newer. Install JDK 21 from https://adoptium.net"
  fi
  if [ -n "${JAVA_HOME:-}" ] && has java && [ "$java_bin" != "$(command -v java)" ]; then
    path_major="$(java_major "$(command -v java)")"
    [ "$path_major" != "$major" ] && note "JAVA_HOME is Java $major but 'java' on PATH is Java $path_major; ./gradlew uses JAVA_HOME"
  fi
fi

echo
echo "For the local demo (./gradlew demo): Node.js or Docker"
mock=""
if has node && has npx; then pass "Node.js $(node --version)"; mock="node"; fi
if has docker; then
  if docker info >/dev/null 2>&1; then pass "Docker $(docker --version | awk '{print $3}' | tr -d ,)"; mock="${mock:-docker}"
  else note "Docker is installed but not running; start Docker Desktop to use it"; fi
fi
[ -z "$mock" ] && miss "Neither Node.js nor a running Docker: install Node.js LTS from https://nodejs.org"
if (exec 3<>/dev/tcp/127.0.0.1/3000) 2>/dev/null; then
  note "Port 3000 is in use; stop whatever is on it before ./gradlew demo"
else
  pass "Port 3000 is free"
fi

echo
echo "Network (the first build downloads Gradle and the libraries)"
for url in https://services.gradle.org https://repo.maven.apache.org/maven2/ https://plugins.gradle.org; do
  if has curl && curl -s -o /dev/null --max-time 10 "$url"; then pass "$url"
  elif has curl; then note "Can't reach $url (proxy or firewall?)"
  fi
done

echo
if [ "$fail" -gt 0 ]; then
  echo "Fix the $fail item(s) marked 'fix', then run this again."
  exit 1
fi
echo "Ready. Next:"
echo "  ./gradlew demo                           # the whole loop on a local mock"
echo "  ./gradlew plan -Ptarget=prod             # what would change in prod (read-only)"
