#!/bin/sh
# Build the real Vulkan test client and its downstream readback observer.
set -eu
set -f

PROBE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROBE_BUILD_DIR=${1:-"$PROBE_DIR/build"}
PROBE_CC=${PROBE_CC:-${CC:-cc}}
mkdir -p "$PROBE_BUILD_DIR"

set -- -std=c11 -O2 -g -Wall -Wextra -Werror \
    "-I$PROBE_DIR" "-I$PROBE_DIR/../include"
if [ -n "${VULKAN_HEADERS_DIR:-}" ]; then
    set -- "$@" "-I$VULKAN_HEADERS_DIR"
fi

# Extra compiler/linker flags follow conventional whitespace-separated CFLAGS.
# Path values containing spaces can use VULKAN_HEADERS_DIR and the output arg.
set -- "$@" ${PROBE_CFLAGS:-}
"$PROBE_CC" "$@" "$PROBE_DIR/probe.c" ${PROBE_LDFLAGS:-} \
    -lvulkan -lX11 -ldl -o "$PROBE_BUILD_DIR/probe"
"$PROBE_CC" "$@" -fPIC -shared -Wl,-Bsymbolic \
    "$PROBE_DIR/observer.c" ${PROBE_LDFLAGS:-} \
    -lvulkan -o "$PROBE_BUILD_DIR/libobserver.so"
cp "$PROBE_DIR/observer.json" "$PROBE_BUILD_DIR/observer.json"
printf 'Built %s/probe and %s/libobserver.so\n' "$PROBE_BUILD_DIR" "$PROBE_BUILD_DIR"
