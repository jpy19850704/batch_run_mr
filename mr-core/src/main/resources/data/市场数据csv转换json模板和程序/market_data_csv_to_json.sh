#!/usr/bin/env bash
set -euo pipefail

show_help() {
  cat <<'EOF'
market_data_csv_to_json.sh
Required:
  --in <csv file>
  --out <json file>
Optional:
  --output-mode market_data|array  (default market_data)
  --pretty true|false              (default true)
  --fill-policy NONE               (accepted for compatibility only)
  --help

Notes:
  - This bash version does NOT support LINEAR fill.
  - Requires GNU awk (gawk) for CSV parsing/sorting helpers.

Examples:
  bash ./bin/market_data_csv_to_json.sh --in ./market.csv --out ./market.json
  bash ./bin/market_data_csv_to_json.sh --in ./market.csv --out ./market.json --output-mode array --pretty false
EOF
}

require_gawk() {
  if ! command -v gawk >/dev/null 2>&1; then
    echo "[ERROR] gawk is required but not found in PATH." >&2
    exit 2
  fi
}

IN=""
OUT=""
OUTPUT_MODE="market_data"
PRETTY="true"
FILL_POLICY="NONE"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in)
      IN="${2:-}"; shift 2;;
    --out)
      OUT="${2:-}"; shift 2;;
    --output-mode)
      OUTPUT_MODE="${2:-}"; shift 2;;
    --pretty)
      PRETTY="${2:-}"; shift 2;;
    --fill-policy)
      FILL_POLICY="${2:-}"; shift 2;;
    --help|-h)
      show_help; exit 0;;
    *)
      echo "[ERROR] Unknown arg: $1" >&2
      show_help
      exit 2;;
  esac
done

if [[ -z "$IN" || -z "$OUT" ]]; then
  echo "[ERROR] --in and --out are required" >&2
  show_help
  exit 2
fi

if [[ "$OUTPUT_MODE" != "market_data" && "$OUTPUT_MODE" != "array" ]]; then
  echo "[ERROR] --output-mode must be market_data or array" >&2
  exit 2
fi

if [[ "$PRETTY" != "true" && "$PRETTY" != "false" ]]; then
  echo "[ERROR] --pretty must be true or false" >&2
  exit 2
fi

if [[ "$FILL_POLICY" != "NONE" && "$FILL_POLICY" != "none" ]]; then
  echo "[ERROR] bash version only supports --fill-policy NONE" >&2
  exit 2
fi

if [[ ! -f "$IN" ]]; then
  echo "[ERROR] input file not found: $IN" >&2
  exit 3
fi

if [[ ! -s "$IN" ]]; then
  echo "[ERROR] empty csv: $IN" >&2
  exit 3
fi

require_gawk
mkdir -p "$(dirname "$OUT")"

tmp_out="$(mktemp "${OUT}.tmp.XXXXXX")"
cleanup_tmp() {
  rm -f "$tmp_out"
}
trap cleanup_tmp EXIT

if gawk \
  -v OUTPUT_MODE="$OUTPUT_MODE" \
  -v PRETTY="$PRETTY" \
  '
BEGIN {
  FPAT = "([^,]*)|(\"([^\"]|\"\")*\")"
  error_count = 0
  warn_count = 0
  builder_count = 0

  supported["IR_SPOT"] = 1
  supported["COMM_SPOT"] = 1
  supported["EQ_SPOT"] = 1
  supported["FX_SPOT"] = 1
  supported["FIXING"] = 1
  supported["IR_FIXING"] = 1
  supported["IR_VOL"] = 1
  supported["FX_VOL"] = 1
  supported["EQ_VOL"] = 1
  supported["COMM_VOL"] = 1

  point_cols["TERM"] = 1
  point_cols["RATE"] = 1
  point_cols["COMM_PRICE"] = 1
  point_cols["EQ_PRICE"] = 1
  point_cols["CURRENCY"] = 1
  point_cols["TRADE_DATE"] = 1
  point_cols["FIXING_VALUE"] = 1
  point_cols["OPTION_TERM"] = 1
  point_cols["UNDERLYING_TERM"] = 1
  point_cols["DELTA"] = 1
  point_cols["VOLATILITY_RATE"] = 1
}

function trim(s) {
  gsub(/^[[:space:]]+|[[:space:]]+$/, "", s)
  return s
}

function strip_bom(s) {
  sub(/^\xef\xbb\xbf/, "", s)
  if (substr(s, 1, 1) == sprintf("%c", 65279)) {
    s = substr(s, 2)
  }
  return s
}

function norm(s) {
  s = trim(s)
  return toupper(s)
}

function unquote_csv(s) {
  s = trim(s)
  if (s ~ /^".*"$/) {
    s = substr(s, 2, length(s) - 2)
    gsub(/""/, "\"", s)
  }
  return s
}

function jesc(s) {
  gsub(/\\/, "\\\\", s)
  gsub(/"/, "\\\"", s)
  gsub(/\r/, "\\r", s)
  gsub(/\n/, "\\n", s)
  gsub(/\t/, "\\t", s)
  return s
}

function jstr(s) {
  return "\"" jesc(s) "\""
}

function is_int(s) {
  return (s ~ /^[-+]?[0-9]+$/)
}

function is_num(s) {
  return (s ~ /^[-+]?(([0-9]+(\.[0-9]*)?)|(\.[0-9]+))([eE][-+]?[0-9]+)?$/)
}

function add_error(msg) {
  errors[++error_count] = msg
}

function add_warn(msg) {
  warns[++warn_count] = msg
}

function add_meta(bkey, key, val) {
  mk = bkey SUBSEP key
  if (!(mk in meta_val)) {
    meta_val[mk] = val
    meta_order_count[bkey]++
    meta_order[bkey SUBSEP meta_order_count[bkey]] = key
  }
}

function merge_meta(bkey, row_line,    i, k, v, mk) {
  for (i = 1; i <= header_count; i++) {
    k = headers[i]
    if (k == "" || (k in point_cols)) {
      continue
    }
    v = vals[k]
    if (v == "") {
      continue
    }
    mk = bkey SUBSEP k
    if (!(mk in meta_val)) {
      add_meta(bkey, k, v)
    } else if (meta_val[mk] != v) {
      add_warn("line " row_line ": meta conflict for " builder_type[bkey] "/" builder_curve_key[bkey] ", key=" k ", keep=" meta_val[mk] ", drop=" v)
    }
  }
}

function point_json(type,    j) {
  if (type == "IR_SPOT") {
    return "{\"TERM\":" p_TERM ",\"RATE\":" p_RATE "}"
  } else if (type == "COMM_SPOT") {
    return "{\"TERM\":" p_TERM ",\"COMM_PRICE\":" p_COMM_PRICE "}"
  } else if (type == "EQ_SPOT") {
    return "{\"TERM\":" p_TERM ",\"EQ_PRICE\":" p_EQ_PRICE "}"
  } else if (type == "FX_SPOT") {
    return "{\"CURRENCY\":" jstr(p_CURRENCY) ",\"RATE\":" p_RATE "}"
  } else if (type == "FIXING" || type == "IR_FIXING") {
    return "{\"TRADE_DATE\":" jstr(p_TRADE_DATE) ",\"FIXING_VALUE\":" p_FIXING_VALUE "}"
  } else if (type == "IR_VOL") {
    return "{\"OPTION_TERM\":" p_OPTION_TERM ",\"UNDERLYING_TERM\":" p_UNDERLYING_TERM ",\"VOLATILITY_RATE\":" p_VOLATILITY_RATE "}"
  } else {
    return "{\"OPTION_TERM\":" p_OPTION_TERM ",\"DELTA\":" p_DELTA ",\"VOLATILITY_RATE\":" p_VOLATILITY_RATE "}"
  }
}

function emit(s) {
  print s
}

function emit_compact_point(bkey, pkey) {
  emit(point_data[bkey SUBSEP pkey])
}

function emit_pretty_point(bkey, pkey, is_last) {
  line = "        " point_data[bkey SUBSEP pkey]
  if (!is_last) {
    line = line ","
  }
  emit(line)
}

function sort_point_keys_for_builder(bkey,    pk, k, a, n, sorted_n, i) {
  delete point_sort_tmp
  n = 0
  for (k in point_data) {
    split(k, a, SUBSEP)
    if (a[1] != bkey) {
      continue
    }
    pk = a[2]
    n++
    point_sort_tmp[pk] = point_sort[bkey SUBSEP pk]
  }
  if (n == 0) {
    sorted_point_count = 0
    return
  }
  sorted_n = asorti(point_sort_tmp, sorted_point_keys, "@val_str_asc")
  sorted_point_count = sorted_n
}

function emit_builder_pretty(bkey, is_last,    mcnt, i, key, v, pcount, pidx, pk) {
  emit("    {")
  mcnt = meta_order_count[bkey]
  for (i = 1; i <= mcnt; i++) {
    key = meta_order[bkey SUBSEP i]
    v = meta_val[bkey SUBSEP key]
    emit("      " jstr(key) ": " jstr(v) ",")
  }

  emit("      \"CURVE_DATA\": [")
  sort_point_keys_for_builder(bkey)
  pcount = sorted_point_count
  for (pidx = 1; pidx <= pcount; pidx++) {
    pk = sorted_point_keys[pidx]
    emit_pretty_point(bkey, pk, pidx == pcount)
  }
  emit("      ]")
  if (is_last) {
    emit("    }")
  } else {
    emit("    },")
  }
}

function emit_builder_compact(bkey, is_last,    mcnt, i, key, v, pcount, pidx, pk, out) {
  out = "{"
  mcnt = meta_order_count[bkey]
  for (i = 1; i <= mcnt; i++) {
    key = meta_order[bkey SUBSEP i]
    v = meta_val[bkey SUBSEP key]
    out = out jstr(key) ":" jstr(v) ","
  }

  out = out "\"CURVE_DATA\":["
  sort_point_keys_for_builder(bkey)
  pcount = sorted_point_count
  for (pidx = 1; pidx <= pcount; pidx++) {
    pk = sorted_point_keys[pidx]
    out = out point_data[bkey SUBSEP pk]
    if (pidx < pcount) {
      out = out ","
    }
  }
  out = out "]}"

  if (!is_last) {
    out = out ","
  }
  emit(out)
}

NR == 1 {
  header_count = NF
  for (i = 1; i <= NF; i++) {
    headers[i] = norm(unquote_csv(strip_bom($i)))
  }
  next
}

{
  if (NF == 1 && trim($1) == "") {
    next
  }

  delete vals
  repeat_header = 1
  for (i = 1; i <= header_count; i++) {
    raw = (i <= NF ? unquote_csv($i) : "")
    nk = headers[i]
    vals[nk] = trim(raw)
    if (norm(raw) != nk) {
      repeat_header = 0
    }
  }
  if (repeat_header) {
    next
  }

  row_line = NR
  type = norm(vals["CURVE_TYPE"])
  if (type == "") {
    add_error("line " row_line ": CURVE_TYPE is required")
    next
  }
  if (!(type in supported)) {
    add_error("line " row_line ": unsupported CURVE_TYPE=" type)
    next
  }

  curve_key = ""
  if (type == "FX_SPOT") {
    curve_key = "FX_SPOT"
  } else if (type == "FIXING" || type == "IR_FIXING") {
    curve_key = vals["FIXING_ID"]
    if (curve_key == "") {
      add_error("line " row_line ": FIXING_ID is required for " type)
      next
    }
  } else {
    curve_key = vals["CURVE_ID"]
    if (curve_key == "") {
      add_error("line " row_line ": CURVE_ID is required for " type)
      next
    }
  }

  bkey = type "|" curve_key
  if (!(bkey in builder_seen)) {
    builder_seen[bkey] = 1
    builder_count++
    builder_order[builder_count] = bkey
    builder_type[bkey] = type
    builder_curve_key[bkey] = curve_key

    add_meta(bkey, "CURVE_TYPE", type)
    if (type == "FIXING" || type == "IR_FIXING") {
      add_meta(bkey, "FIXING_ID", curve_key)
    } else if (type != "FX_SPOT") {
      add_meta(bkey, "CURVE_ID", curve_key)
    }
  }

  merge_meta(bkey, row_line)

  point_ok = 1

  if (type == "IR_SPOT") {
    if (vals["TERM"] == "") {
      add_error("line " row_line ": TERM is required")
      point_ok = 0
    } else if (!is_int(vals["TERM"])) {
      add_error("line " row_line ": TERM must be integer: " vals["TERM"])
      point_ok = 0
    }
    if (vals["RATE"] == "") {
      add_error("line " row_line ": RATE is required")
      point_ok = 0
    } else if (!is_num(vals["RATE"])) {
      add_error("line " row_line ": RATE must be number: " vals["RATE"])
      point_ok = 0
    }
    if (point_ok) {
      p_TERM = vals["TERM"] + 0
      p_RATE = vals["RATE"] + 0
      pkey = p_TERM
      skey = sprintf("%020d", p_TERM)
    }
  } else if (type == "COMM_SPOT") {
    if (vals["TERM"] == "") {
      add_error("line " row_line ": TERM is required")
      point_ok = 0
    } else if (!is_int(vals["TERM"])) {
      add_error("line " row_line ": TERM must be integer: " vals["TERM"])
      point_ok = 0
    }
    if (vals["COMM_PRICE"] == "") {
      add_error("line " row_line ": COMM_PRICE is required")
      point_ok = 0
    } else if (!is_num(vals["COMM_PRICE"])) {
      add_error("line " row_line ": COMM_PRICE must be number: " vals["COMM_PRICE"])
      point_ok = 0
    }
    if (point_ok) {
      p_TERM = vals["TERM"] + 0
      p_COMM_PRICE = vals["COMM_PRICE"] + 0
      pkey = p_TERM
      skey = sprintf("%020d", p_TERM)
    }
  } else if (type == "EQ_SPOT") {
    if (vals["TERM"] == "") {
      add_error("line " row_line ": TERM is required")
      point_ok = 0
    } else if (!is_int(vals["TERM"])) {
      add_error("line " row_line ": TERM must be integer: " vals["TERM"])
      point_ok = 0
    }
    if (vals["EQ_PRICE"] == "") {
      add_error("line " row_line ": EQ_PRICE is required")
      point_ok = 0
    } else if (!is_num(vals["EQ_PRICE"])) {
      add_error("line " row_line ": EQ_PRICE must be number: " vals["EQ_PRICE"])
      point_ok = 0
    }
    if (point_ok) {
      p_TERM = vals["TERM"] + 0
      p_EQ_PRICE = vals["EQ_PRICE"] + 0
      pkey = p_TERM
      skey = sprintf("%020d", p_TERM)
    }
  } else if (type == "FX_SPOT") {
    if (vals["CURRENCY"] == "") {
      add_error("line " row_line ": CURRENCY is required")
      point_ok = 0
    }
    if (vals["RATE"] == "") {
      add_error("line " row_line ": RATE is required")
      point_ok = 0
    } else if (!is_num(vals["RATE"])) {
      add_error("line " row_line ": RATE must be number: " vals["RATE"])
      point_ok = 0
    }
    if (point_ok) {
      p_CURRENCY = toupper(vals["CURRENCY"])
      p_RATE = vals["RATE"] + 0
      pkey = p_CURRENCY
      skey = p_CURRENCY
    }
  } else if (type == "FIXING" || type == "IR_FIXING") {
    if (vals["TRADE_DATE"] == "") {
      add_error("line " row_line ": TRADE_DATE is required")
      point_ok = 0
    } else if (vals["TRADE_DATE"] !~ /^[0-9]{8}$/) {
      add_error("line " row_line ": TRADE_DATE must be yyyyMMdd at line " row_line ": " vals["TRADE_DATE"])
      point_ok = 0
    }
    if (vals["FIXING_VALUE"] == "") {
      add_error("line " row_line ": FIXING_VALUE is required")
      point_ok = 0
    } else if (!is_num(vals["FIXING_VALUE"])) {
      add_error("line " row_line ": FIXING_VALUE must be number: " vals["FIXING_VALUE"])
      point_ok = 0
    }
    if (point_ok) {
      p_TRADE_DATE = vals["TRADE_DATE"]
      p_FIXING_VALUE = vals["FIXING_VALUE"] + 0
      pkey = p_TRADE_DATE
      skey = p_TRADE_DATE
    }
  } else if (type == "IR_VOL") {
    if (vals["OPTION_TERM"] == "") {
      add_error("line " row_line ": OPTION_TERM is required")
      point_ok = 0
    } else if (!is_int(vals["OPTION_TERM"])) {
      add_error("line " row_line ": OPTION_TERM must be integer: " vals["OPTION_TERM"])
      point_ok = 0
    }
    if (vals["UNDERLYING_TERM"] == "") {
      add_error("line " row_line ": UNDERLYING_TERM is required")
      point_ok = 0
    } else if (!is_int(vals["UNDERLYING_TERM"])) {
      add_error("line " row_line ": UNDERLYING_TERM must be integer: " vals["UNDERLYING_TERM"])
      point_ok = 0
    }
    if (vals["VOLATILITY_RATE"] == "") {
      add_error("line " row_line ": VOLATILITY_RATE is required")
      point_ok = 0
    } else if (!is_num(vals["VOLATILITY_RATE"])) {
      add_error("line " row_line ": VOLATILITY_RATE must be number: " vals["VOLATILITY_RATE"])
      point_ok = 0
    }
    if (point_ok) {
      p_OPTION_TERM = vals["OPTION_TERM"] + 0
      p_UNDERLYING_TERM = vals["UNDERLYING_TERM"] + 0
      p_VOLATILITY_RATE = vals["VOLATILITY_RATE"] + 0
      pkey = p_OPTION_TERM "@" p_UNDERLYING_TERM
      skey = sprintf("%020d@%020d", p_OPTION_TERM, p_UNDERLYING_TERM)
    }
  } else {
    if (vals["OPTION_TERM"] == "") {
      add_error("line " row_line ": OPTION_TERM is required")
      point_ok = 0
    } else if (!is_int(vals["OPTION_TERM"])) {
      add_error("line " row_line ": OPTION_TERM must be integer: " vals["OPTION_TERM"])
      point_ok = 0
    }
    if (vals["DELTA"] == "") {
      add_error("line " row_line ": DELTA is required")
      point_ok = 0
    } else if (!is_num(vals["DELTA"])) {
      add_error("line " row_line ": DELTA must be number: " vals["DELTA"])
      point_ok = 0
    }
    if (vals["VOLATILITY_RATE"] == "") {
      add_error("line " row_line ": VOLATILITY_RATE is required")
      point_ok = 0
    } else if (!is_num(vals["VOLATILITY_RATE"])) {
      add_error("line " row_line ": VOLATILITY_RATE must be number: " vals["VOLATILITY_RATE"])
      point_ok = 0
    }
    if (point_ok) {
      p_OPTION_TERM = vals["OPTION_TERM"] + 0
      p_DELTA = vals["DELTA"] + 0
      p_VOLATILITY_RATE = vals["VOLATILITY_RATE"] + 0
      pkey = p_OPTION_TERM "@" p_DELTA
      skey = sprintf("%020d@%+030.15f", p_OPTION_TERM, p_DELTA)
    }
  }

  if (!point_ok) {
    next
  }

  pjson = point_json(type)
  pdkey = bkey SUBSEP pkey
  if ((pdkey in point_data) && point_data[pdkey] != pjson) {
    add_warn("line " row_line ": duplicate point overwritten for " type "/" curve_key ", pointKey=" pkey)
  }
  point_data[pdkey] = pjson
  point_sort[pdkey] = skey
}

END {
  if (error_count > 0) {
    for (i = 1; i <= error_count; i++) {
      print "[ERROR] " errors[i] > "/dev/stderr"
    }
    exit 4
  }

  if (PRETTY == "true") {
    if (OUTPUT_MODE == "array") {
      emit("[")
      for (bi = 1; bi <= builder_count; bi++) {
        bkey = builder_order[bi]
        emit_builder_pretty(bkey, bi == builder_count)
      }
      emit("]")
    } else {
      emit("{")
      emit("  \"market_data\": [")
      for (bi = 1; bi <= builder_count; bi++) {
        bkey = builder_order[bi]
        emit_builder_pretty(bkey, bi == builder_count)
      }
      emit("  ]")
      emit("}")
    }
  } else {
    if (OUTPUT_MODE == "array") {
      emit("[")
      for (bi = 1; bi <= builder_count; bi++) {
        bkey = builder_order[bi]
        emit_builder_compact(bkey, bi == builder_count)
      }
      emit("]")
    } else {
      emit("{\"market_data\":[")
      for (bi = 1; bi <= builder_count; bi++) {
        bkey = builder_order[bi]
        emit_builder_compact(bkey, bi == builder_count)
      }
      emit("]}")
    }
  }

  print "Converted curves: " builder_count > "/dev/stderr"
  print "Warnings: " warn_count > "/dev/stderr"
  for (i = 1; i <= warn_count; i++) {
    print "[WARN] " warns[i] > "/dev/stderr"
  }
}
' "$IN" > "$tmp_out"; then
  mv "$tmp_out" "$OUT"
  trap - EXIT
else
  rc=$?
  cat "$tmp_out" >&2 || true
  exit $rc
fi
