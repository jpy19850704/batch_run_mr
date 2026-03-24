#!/usr/bin/env bash
set -euo pipefail

show_help() {
  cat <<'EOF'
curvegeneration_csv_to_json.sh
Required:
  --in <csv file>
  --out <json file>
Optional:
  --pretty true|false (default true)
  --help

CSV format:
  - One row per term point.
  - Use one unified header; repeated headers in body are ignored.
  - Group key: CONVERSION_TYPE + CURVE_ID + DATA_DATE.

Supported CONVERSION_TYPE:
  - ZeroCurveBootstrap
  - FxImpliedCurveConstruct
  - ZeroCurveSubtract
  - VolRrbf2Delta

Examples:
  bash ./bin/curvegeneration_csv_to_json.sh --in ./curve_input.csv --out ./curve_input.json
  bash ./bin/curvegeneration_csv_to_json.sh --in ./curve_input.csv --out ./curve_input.json --pretty false
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
PRETTY="true"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --in)
      IN="${2:-}"; shift 2;;
    --out)
      OUT="${2:-}"; shift 2;;
    --pretty)
      PRETTY="${2:-}"; shift 2;;
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

if [[ "$PRETTY" != "true" && "$PRETTY" != "false" ]]; then
  echo "[ERROR] --pretty must be true or false" >&2
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
  -v PRETTY="$PRETTY" \
  '
BEGIN {
  FPAT = "([^,]*)|(\"([^\"]|\"\")*\")"
  error_count = 0
  warn_count = 0
  builder_count = 0

  type_map["ZEROCURVEBOOTSTRAP"] = "ZeroCurveBootstrap"
  type_map["FXIMPLIEDCURVECONSTRUCT"] = "FxImpliedCurveConstruct"
  type_map["ZEROCURVESUBTRACT"] = "ZeroCurveSubtract"
  type_map["VOLRRBF2DELTA"] = "VolRrbf2Delta"

  point_cols["TERM_CODE"] = 1
  point_cols["TERM_TYPE"] = 1
  point_cols["TERM_VALUE"] = 1
  point_cols["TERM_DAYCOUNT"] = 1
  point_cols["TERM_FRQ"] = 1
  point_cols["START_TERM"] = 1
  point_cols["FWD_RATE"] = 1
  point_cols["ATM_VOL"] = 1
  point_cols["RR_VOL"] = 1
  point_cols["BF_VOL"] = 1
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

function is_num(s) {
  return (s ~ /^[-+]?(([0-9]+(\.[0-9]*)?)|(\.[0-9]+))([eE][-+]?[0-9]+)?$/)
}

function is_int(s) {
  return (s ~ /^[-+]?[0-9]+$/)
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
      add_warn("line " row_line ": meta conflict for " builder_type[bkey] "/" builder_curve_id[bkey] ", key=" k ", keep=" meta_val[mk] ", drop=" v)
    }
  }
}

function meta_json_value(key, val) {
  if (key == "DAY_OFF" || key == "FX_SPOT") {
    return (val + 0)
  }
  return jstr(val)
}

function push_point(bkey, pkey, pjson, row_line) {
  pdkey = bkey SUBSEP pkey
  if ((pdkey in point_data) && point_data[pdkey] != pjson) {
    add_warn("line " row_line ": duplicate point overwritten for " builder_type[bkey] "/" builder_curve_id[bkey] ", pointKey=" pkey)
  }
  if (!(pdkey in point_data)) {
    point_order_count[bkey]++
    point_order[bkey SUBSEP point_order_count[bkey]] = pkey
  }
  point_data[pdkey] = pjson
}

function emit(s) {
  print s
}

function emit_builder_pretty(bkey, is_last,    mcnt, i, k, v, pcount, pidx, pkey, pjson, line) {
  emit("  {")
  mcnt = meta_order_count[bkey]
  for (i = 1; i <= mcnt; i++) {
    k = meta_order[bkey SUBSEP i]
    v = meta_val[bkey SUBSEP k]
    emit("    " jstr(k) ": " meta_json_value(k, v) ",")
  }

  emit("    \"CURVE_DATA\": [")
  pcount = point_order_count[bkey]
  for (pidx = 1; pidx <= pcount; pidx++) {
    pkey = point_order[bkey SUBSEP pidx]
    pjson = point_data[bkey SUBSEP pkey]
    line = "      " pjson
    if (pidx < pcount) {
      line = line ","
    }
    emit(line)
  }
  emit("    ]")

  if (is_last) {
    emit("  }")
  } else {
    emit("  },")
  }
}

function emit_builder_compact(bkey, is_last,    mcnt, i, k, v, pcount, pidx, pkey, pjson, out) {
  out = "{"
  mcnt = meta_order_count[bkey]
  for (i = 1; i <= mcnt; i++) {
    k = meta_order[bkey SUBSEP i]
    v = meta_val[bkey SUBSEP k]
    out = out jstr(k) ":" meta_json_value(k, v) ","
  }
  out = out "\"CURVE_DATA\":["
  pcount = point_order_count[bkey]
  for (pidx = 1; pidx <= pcount; pidx++) {
    pkey = point_order[bkey SUBSEP pidx]
    pjson = point_data[bkey SUBSEP pkey]
    out = out pjson
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
  type_raw = norm(vals["CONVERSION_TYPE"])
  if (type_raw == "") {
    add_error("line " row_line ": CONVERSION_TYPE is required")
    next
  }
  if (!(type_raw in type_map)) {
    add_error("line " row_line ": unsupported CONVERSION_TYPE=" vals["CONVERSION_TYPE"])
    next
  }
  type = type_map[type_raw]

  curve_id = vals["CURVE_ID"]
  if (curve_id == "") {
    add_error("line " row_line ": CURVE_ID is required")
    next
  }

  data_date = vals["DATA_DATE"]
  if (data_date == "") {
    add_error("line " row_line ": DATA_DATE is required")
    next
  }
  if (data_date !~ /^[0-9]{8}$/) {
    add_error("line " row_line ": DATA_DATE must be yyyyMMdd: " data_date)
    next
  }

  if (vals["DAY_OFF"] != "" && !is_int(vals["DAY_OFF"])) {
    add_error("line " row_line ": DAY_OFF must be integer: " vals["DAY_OFF"])
    next
  }
  if (vals["FX_SPOT"] != "" && !is_num(vals["FX_SPOT"])) {
    add_error("line " row_line ": FX_SPOT must be number: " vals["FX_SPOT"])
    next
  }

  if (type == "FxImpliedCurveConstruct") {
    if (vals["BASE_DISCOUNT_CURVE"] == "") {
      add_error("line " row_line ": BASE_DISCOUNT_CURVE is required for " type)
      next
    }
  } else if (type == "ZeroCurveSubtract") {
    if (vals["YC_CURVE_CODE"] == "") {
      add_error("line " row_line ": YC_CURVE_CODE is required for " type)
      next
    }
    if (vals["RF_CURVE_CODE"] == "") {
      add_error("line " row_line ": RF_CURVE_CODE is required for " type)
      next
    }
  } else if (type == "VolRrbf2Delta") {
    if (vals["BASE_DISCOUNT_CURVE"] == "") {
      add_error("line " row_line ": BASE_DISCOUNT_CURVE is required for " type)
      next
    }
    if (vals["UNDERLYING_DISCOUNT_CURVE"] == "") {
      add_error("line " row_line ": UNDERLYING_DISCOUNT_CURVE is required for " type)
      next
    }
    if (vals["FX_SPOT"] == "") {
      add_error("line " row_line ": FX_SPOT is required for " type)
      next
    }
    if (!is_num(vals["FX_SPOT"]) || vals["FX_SPOT"] + 0 <= 0) {
      add_error("line " row_line ": FX_SPOT must be positive number for " type ": " vals["FX_SPOT"])
      next
    }
  }

  bkey = type "|" curve_id "|" data_date
  if (!(bkey in builder_seen)) {
    builder_seen[bkey] = 1
    builder_count++
    builder_order[builder_count] = bkey
    builder_type[bkey] = type
    builder_curve_id[bkey] = curve_id

    add_meta(bkey, "CONVERSION_TYPE", type)
    add_meta(bkey, "CURVE_ID", curve_id)
    add_meta(bkey, "DATA_DATE", data_date)
  }

  merge_meta(bkey, row_line)

  if (type == "ZeroCurveSubtract") {
    next
  }

  # Row-level point parse
  if (type == "ZeroCurveBootstrap") {
    term_code = vals["TERM_CODE"]
    term_type = norm(vals["TERM_TYPE"])
    term_value = vals["TERM_VALUE"]

    if (term_code == "") {
      add_error("line " row_line ": TERM_CODE is required for " type)
      next
    }
    if (term_type == "") {
      add_error("line " row_line ": TERM_TYPE is required for " type)
      next
    }
    if (term_type != "ZERO" && term_type != "SWAP") {
      add_error("line " row_line ": TERM_TYPE must be ZERO or SWAP for " type ": " vals["TERM_TYPE"])
      next
    }
    if (term_value == "") {
      add_error("line " row_line ": TERM_VALUE is required for " type)
      next
    }
    if (!is_num(term_value)) {
      add_error("line " row_line ": TERM_VALUE must be number: " term_value)
      next
    }
    if (term_type == "SWAP" && vals["TERM_FRQ"] == "") {
      add_error("line " row_line ": TERM_FRQ is required when TERM_TYPE=SWAP")
      next
    }

    pkey = term_code "@" vals["START_TERM"]
    pjson = "{\"TERM_CODE\":" jstr(term_code) ",\"TERM_TYPE\":" jstr(term_type) ",\"TERM_VALUE\":" (term_value + 0)
    if (vals["TERM_DAYCOUNT"] != "") {
      pjson = pjson ",\"TERM_DAYCOUNT\":" jstr(vals["TERM_DAYCOUNT"])
    }
    if (vals["TERM_FRQ"] != "") {
      pjson = pjson ",\"TERM_FRQ\":" jstr(vals["TERM_FRQ"])
    }
    if (vals["START_TERM"] != "") {
      pjson = pjson ",\"START_TERM\":" jstr(vals["START_TERM"])
    }
    pjson = pjson "}"
    push_point(bkey, pkey, pjson, row_line)

  } else if (type == "FxImpliedCurveConstruct") {
    term_code = vals["TERM_CODE"]
    fwd_rate = vals["FWD_RATE"]
    if (term_code == "") {
      add_error("line " row_line ": TERM_CODE is required for " type)
      next
    }
    if (fwd_rate == "") {
      add_error("line " row_line ": FWD_RATE is required for " type)
      next
    }
    if (!is_num(fwd_rate)) {
      add_error("line " row_line ": FWD_RATE must be number: " fwd_rate)
      next
    }

    pkey = term_code
    pjson = "{\"TERM_CODE\":" jstr(term_code) ",\"FWD_RATE\":" (fwd_rate + 0) "}"
    push_point(bkey, pkey, pjson, row_line)

  } else if (type == "VolRrbf2Delta") {
    term_code = vals["TERM_CODE"]
    atm_vol = vals["ATM_VOL"]
    rr_vol = vals["RR_VOL"]
    bf_vol = vals["BF_VOL"]

    if (term_code == "") {
      add_error("line " row_line ": TERM_CODE is required for " type)
      next
    }
    if (atm_vol == "" || rr_vol == "" || bf_vol == "") {
      add_error("line " row_line ": ATM_VOL/RR_VOL/BF_VOL are required for " type)
      next
    }
    if (!is_num(atm_vol) || !is_num(rr_vol) || !is_num(bf_vol)) {
      add_error("line " row_line ": ATM_VOL/RR_VOL/BF_VOL must be numbers")
      next
    }

    pkey = term_code
    pjson = "{\"TERM_CODE\":" jstr(term_code) ",\"ATM_VOL\":" (atm_vol + 0) ",\"RR_VOL\":" (rr_vol + 0) ",\"BF_VOL\":" (bf_vol + 0) "}"
    push_point(bkey, pkey, pjson, row_line)
  }
}

END {
  if (error_count > 0) {
    for (i = 1; i <= error_count; i++) {
      print "[ERROR] " errors[i] > "/dev/stderr"
    }
    exit 4
  }

  # Ensure point types have point rows
  for (bi = 1; bi <= builder_count; bi++) {
    bkey = builder_order[bi]
    t = builder_type[bkey]
    if (t == "ZeroCurveBootstrap" || t == "FxImpliedCurveConstruct" || t == "VolRrbf2Delta") {
      if (point_order_count[bkey] == 0) {
        print "[ERROR] curve has no CURVE_DATA points: " t "/" builder_curve_id[bkey] > "/dev/stderr"
        exit 4
      }
    }
  }

  if (PRETTY == "true") {
    emit("[")
    for (bi = 1; bi <= builder_count; bi++) {
      bkey = builder_order[bi]
      emit_builder_pretty(bkey, bi == builder_count)
    }
    emit("]")
  } else {
    emit("[")
    for (bi = 1; bi <= builder_count; bi++) {
      bkey = builder_order[bi]
      emit_builder_compact(bkey, bi == builder_count)
    }
    emit("]")
  }

  print "Converted curve inputs: " builder_count > "/dev/stderr"
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
