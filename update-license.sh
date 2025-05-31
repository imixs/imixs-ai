#!/bin/bash

# Neues Lizenz-Header
read -r -d '' NEW_HEADER <<'EOF'
/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which is available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/
EOF

# Check all .java-files 
find . -name "*.java" | while read -r file; do
    echo "processing: $file"

    # Check for old header
    if grep -q "Imixs Software Solutions GmbH" "$file" && grep -q "This program is free software" "$file"; then
        echo " -> found old header..."
        # remove old comment blog (from /* to */)
        awk '/\/\*/,/\*\// {next} {print}' "$file" > "$file.tmp"

    # check for missing header (starts direkt with package or import)
    elif grep -q -m 1 -E "^\s*(package|import)" "$file"; then
        FIRST_LINE=$(head -n 1 "$file")
        if [[ $FIRST_LINE =~ ^[[:space:]]*(package|import) ]]; then
            echo " -> missing header - adding now"
            cp "$file" "$file.tmp"
        else
            echo " -> no changes."
            continue
        fi

    else
        echo " -> already up to date."
        continue
    fi

    # update file
    {
        echo "$NEW_HEADER"
        echo ""
        cat "$file.tmp"
    } > "$file"

    # clean up
    rm "$file.tmp"
done

echo "completed."
