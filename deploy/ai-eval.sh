#!/usr/bin/env bash
# Runs the assistant eval set against a deployed instance. No interaction needed.
#   deploy/ai-eval.sh [base-url] [--fresh] [--questions file.json]
# base-url   default https://bookmanagement.daniellaera.com (or $AI_EVAL_BASE_URL)
# --fresh    append a nonce to each question so the 24 h answer cache is bypassed (real token numbers)
# Auth (POST /ask needs a user): $AI_EVAL_TOKEN (a JWT), or $AI_EVAL_EMAIL + $AI_EVAL_PASSWORD, or - by default -
#            a throwaway user ai-eval-<timestamp>@example.invalid is signed up (its email is printed so you can delete it).
# Expectations are resolved against the live catalogue, so they survive data changes.
# Output: one line per question (PASS/FAIL, latency, input/output tokens, tool calls), then a summary.
# Exit code: 0 when at least $AI_EVAL_MIN_PASS (default: all) questions pass, 1 otherwise. Never prints secrets.
# Cost note: one /ask per question, counted against that user's daily AI limit (20 by default; the set has 15).
set -euo pipefail
set +x

BASE="${AI_EVAL_BASE_URL:-https://bookmanagement.daniellaera.com}"
FRESH=0
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUESTIONS="$HERE/../backend/src/test/resources/ai-eval/questions.json"
while [ $# -gt 0 ]; do
  case "$1" in
    --fresh) FRESH=1 ;;
    --questions) QUESTIONS="$2"; shift ;;
    http*) BASE="$1" ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
[ -f "$QUESTIONS" ] || { echo "questions file not found: $QUESTIONS" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }

export AI_EVAL_BASE_URL="${BASE%/}" AI_EVAL_FRESH="$FRESH" AI_EVAL_QUESTIONS="$QUESTIONS"
exec python3 -I - <<'PY'
import json, os, re, secrets, statistics, sys, time, urllib.error, urllib.request

BASE = os.environ["AI_EVAL_BASE_URL"]
FRESH = os.environ["AI_EVAL_FRESH"] == "1"
MIN_PASS = os.environ.get("AI_EVAL_MIN_PASS")

def http(method, path, body=None, token=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, None

# ---- auth ----
token = os.environ.get("AI_EVAL_TOKEN", "")
if not token:
    email = os.environ.get("AI_EVAL_EMAIL", "")
    password = os.environ.get("AI_EVAL_PASSWORD", "")
    if not email:
        email = "ai-eval-%d@example.invalid" % int(time.time())
        password = "Ev-" + secrets.token_hex(10)
        code, _ = http("POST", "/api/v3/auth/signup",
                       {"firstName": "AI", "lastName": "Eval", "email": email, "password": password})
        if code not in (200, 201, 202):
            sys.exit("signup failed (HTTP %s)" % code)
        print("created throwaway user %s (delete it afterwards)" % email)
    code, body = http("POST", "/api/v3/auth/signin", {"email": email, "password": password})
    if code != 200 or not body or not body.get("token"):
        sys.exit("signin failed (HTTP %s)" % code)
    token = body["token"]

# ---- live catalogue, for expectations ----
books, page = [], 0
while True:
    code, body = http("GET", "/api/v3/book?page=%d&size=50&sort=title,asc" % page)
    if code != 200 or not body:
        sys.exit("cannot read the catalogue (HTTP %s)" % code)
    books += body["content"]
    page += 1
    if page >= body.get("totalPages", 1):
        break
TOTAL = len(books)
AVAILABLE = sum(1 for b in books if b.get("isAvailable"))
def by_genre(g): return [b for b in books if (b.get("genre") or "").lower() == g.lower()]
def by_author(a): return [b for b in books if a.lower() in (b.get("author") or "").lower()]

WORDS = ["zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen",
         "fourteen","fifteen","sixteen","seventeen","eighteen","nineteen","twenty","twenty-one","twenty-two","twenty-three",
         "twenty-four","twenty-five","twenty-six","twenty-seven","twenty-eight","twenty-nine","thirty","thirty-one",
         "thirty-two","thirty-three","thirty-four","thirty-five","thirty-six","thirty-seven","thirty-eight","thirty-nine","forty"]

def resolve(v):
    def sub(m):
        key, _, arg = m.group(1).partition(":")
        return str({"TOTAL": TOTAL, "AVAILABLE": AVAILABLE,
                    "GENRE_COUNT": len(by_genre(arg)), "AUTHOR_COUNT": len(by_author(arg))}[key])
    return re.sub(r"\{\{([^}]+)\}\}", sub, v)

def has_number(answer, n):
    n = int(n)
    if re.search(r"(?<![\d.,])%d(?![\d])" % n, answer):
        return True
    return n < len(WORDS) and re.search(r"\b%s\b" % re.escape(WORDS[n]), answer.lower()) is not None

def check(answer, exp):
    low = answer.lower()
    problems = []
    if "number" in exp and not has_number(answer, resolve(exp["number"])):
        problems.append("expected the number %s" % resolve(exp["number"]))
    if "contains_all" in exp:
        miss = [x for x in exp["contains_all"] if x.lower() not in low]
        if miss: problems.append("missing " + ", ".join(miss))
    if "contains_any" in exp and not any(x.lower() in low for x in exp["contains_any"]):
        problems.append("none of: " + " | ".join(exp["contains_any"]))
    if "titles_of_genre" in exp:
        titles = [b["title"] for b in by_genre(exp["titles_of_genre"])][:5]
        miss = [t for t in titles if t.lower() not in low]
        if not titles: problems.append("no catalogue books in genre " + exp["titles_of_genre"])
        elif miss: problems.append("missing titles: " + ", ".join(miss))
    if "titles_any_of_genre" in exp:
        titles = [b["title"] for b in by_genre(exp["titles_any_of_genre"])]
        if not any(t.lower() in low for t in titles): problems.append("no %s title named" % exp["titles_any_of_genre"])
    if "availability_of" in exp:
        match = [b for b in books if b["title"].lower() == exp["availability_of"].lower()]
        if not match:
            problems.append("title not in catalogue")
        elif match[0].get("isAvailable"):
            if not any(w in low for w in ("available", "yes", "can be borrowed")) or "not available" in low or "unavailable" in low:
                problems.append("should say it is available")
        elif not any(w in low for w in ("borrowed", "not available", "unavailable", "checked out", "on loan")):
            problems.append("should say it is borrowed")
    for bad in exp.get("not_contains", []):
        if bad.lower() in low: problems.append("must not contain '%s'" % bad)
    return problems

spec = json.load(open(os.environ["AI_EVAL_QUESTIONS"]))["questions"]
print("base=%s  catalogue: %d books, %d available  questions: %d  fresh=%s\n" % (BASE, TOTAL, AVAILABLE, len(spec), FRESH))
print("%-18s %-5s %7s %6s %6s %5s  %s" % ("id", "res", "ms", "in", "out", "tools", "note"))

rows, passed = [], 0
for q in spec:
    question = q["question"] + ((" (ref %s)" % secrets.token_hex(3)) if FRESH else "")
    t0 = time.time()
    code, body = http("POST", "/api/v3/ai/books/ask", {"question": question}, token)
    ms = int((time.time() - t0) * 1000)
    if code != 200 or not body or not body.get("answer"):
        res, note, tin, tout, tools = "FAIL", "HTTP %s, no answer" % code, 0, 0, 0
    else:
        problems = check(body["answer"], q["expect"])
        res = "FAIL" if problems else "PASS"
        note = "; ".join(problems) if problems else ("cached" if body.get("cached") else "")
        tin, tout, tools = body.get("inputTokens", 0), body.get("outputTokens", 0), body.get("toolCalls", 0)
        if problems:
            note += "  -> " + body["answer"].replace("\n", " ")[:160]
    passed += res == "PASS"
    rows.append((ms, tin, tout, tools, res))
    print("%-18s %-5s %7d %6d %6d %5d  %s" % (q["id"], res, ms, tin, tout, tools, note))

lat = sorted(r[0] for r in rows)
p95 = lat[min(len(lat) - 1, int(round(0.95 * (len(lat) - 1))))]
print("\nresult: %d/%d passed | latency avg %d ms, p95 %d ms | tokens in %d, out %d (total %d) | tool calls %d"
      % (passed, len(rows), statistics.mean(lat), p95, sum(r[1] for r in rows), sum(r[2] for r in rows),
         sum(r[1] + r[2] for r in rows), sum(r[3] for r in rows)))
if not FRESH:
    print("note: cached answers report 0 tokens; use --fresh for real token numbers")
need = int(MIN_PASS) if MIN_PASS else len(rows)
sys.exit(0 if passed >= need else 1)
PY
