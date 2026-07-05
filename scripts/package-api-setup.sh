#!/usr/bin/env bash
set -euo pipefail

find_archiver() {
    local candidate
    for candidate in \
        7z 7za 7zr \
        /run/current-system/sw/bin/7z /run/current-system/sw/bin/7za /run/current-system/sw/bin/7zr \
        /usr/bin/7z /usr/bin/7za /usr/bin/7zr \
        /usr/local/bin/7z /usr/local/bin/7za /usr/local/bin/7zr \
        /opt/homebrew/bin/7z /opt/homebrew/bin/7za /opt/homebrew/bin/7zr
    do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return 0
        fi
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

archiver="$(find_archiver)" || {
    echo "7z, 7za, or 7zr is required" >&2
    exit 1
}

rm -rf build/api-setup build/aris-luagen-api-setup.7z build/aris-luagen-api-setup.7z.sha256
mkdir -p build/api-setup/apis

found_api=false
for file in build/generated/ksp/main/resources/apis/*.json; do
    [[ -f "$file" ]] || continue
    cp "$file" build/api-setup/apis/
    found_api=true
done

if [[ "$found_api" != true ]]; then
    echo "No generated API schema found under build/generated/ksp/main/resources/apis" >&2
    exit 1
fi

cp -R apis/i18n build/api-setup/apis/

(
    cd build/api-setup
    "$archiver" a -t7z ../aris-luagen-api-setup.7z apis
)

if command -v sha256sum >/dev/null 2>&1; then
    checksum="$(sha256sum build/aris-luagen-api-setup.7z)"
else
    checksum="$(shasum -a 256 build/aris-luagen-api-setup.7z)"
fi

printf '%s\n' "${checksum%% *}" > build/aris-luagen-api-setup.7z.sha256
