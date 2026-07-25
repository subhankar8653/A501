if [ "${ENABLE_SELF_UPDATE:-false}" = "true" ]; then
    uv run update.py
fi
uv run -m Backend