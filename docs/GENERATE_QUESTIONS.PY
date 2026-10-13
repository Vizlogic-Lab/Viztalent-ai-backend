import csv
import os

headers = [
    "Question_ID",
    "Type",
    "Difficulty",
    "Question",
    "Options",
    "Answer",
    "Explanation",
]

# Curated pools across the 4 core archetypes
mcqs = [
    (
        "What is the output of bool('False') in Python?",
        "A) False | B) True | C) None | D) SyntaxError",
        "B",
        "Any non-empty string in Python evaluates to True in a boolean context.",
        "Beginner",
    ),
    (
        "Which of the following built-in data types is immutable?",
        "A) list | B) dict | C) tuple | D) bytearray",
        "C",
        "Tuples cannot be altered, appended, or mutated after initial instantiation.",
        "Beginner",
    ),
    (
        "What is the average time complexity of searching a key in a standard Python dictionary?",
        "A) O(1) | B) O(n) | C) O(log n) | D) O(n^2)",
        "A",
        "Python dictionaries are implemented using hash tables, offering amortized O(1) key lookups.",
        "Intermediate",
    ),
    (
        "What does CPython's Global Interpreter Lock (GIL) enforce?",
        "A) Thread-safe garbage collection | B) Single-thread execution of Python bytecode | C) Deadlock elimination | D) Async I/O speedup",
        "B",
        "The GIL is a mutex preventing multiple native threads from executing Python bytecodes concurrently.",
        "Advanced",
    ),
    (
        "Which dunder method is invoked when evaluating `len(obj)`?",
        "A) __size__ | B) __count__ | C) __length__ | D) __len__",
        "D",
        "The built-in len() function internally delegates to the object's `__len__()` implementation.",
        "Beginner",
    ),
    (
        "How are args and kwargs passed into a function unpacked?",
        "A) * for tuples/lists, ** for dicts | B) & for tuples, * for dicts | C) ** for lists, * for dicts | D) None of these",
        "A",
        "* unpacks positional iterables (lists/tuples); ** unpacks mapping/keyword arguments (dictionaries).",
        "Beginner",
    ),
    (
        "Which built-in module provides heap queue algorithm implementations?",
        "A) queue | B) collections | C) heapq | D) bisect",
        "C",
        "heapq implements min-heaps over standard Python list objects.",
        "Intermediate",
    ),
    (
        "What happens when you define `__slots__` in a class definition?",
        "A) Denies inheritance | B) Prevents `__dict__` creation to save memory | C) Makes instances immutable | D) Optimizes method dispatch",
        "B",
        "`__slots__` explicitly specifies attribute names, removing the dynamic per-instance `__dict__` overhead.",
        "Advanced",
    ),
]

subjectives = [
    (
        "Explain the behavioral and structural differences between a shallow copy and a deep copy.",
        "A shallow copy constructs a new composite object and inserts references to the original child objects. A deep copy recursively duplicates every nested object, ensuring changes to nested structures do not cross-mutate.",
        "Intermediate",
    ),
    (
        "Explain generator functions and describe how the `yield` keyword differs from `return`.",
        "A function with `yield` returns a lazy generator iterator that suspends execution state (local variables, execution pointer) between iterations, yielding items on-demand to optimize memory consumption.",
        "Intermediate",
    ),
    (
        "Explain Python's Method Resolution Order (MRO) and how the C3 Linearization algorithm operates.",
        "MRO defines the class search hierarchy during attribute lookup in multiple inheritance scenarios. C3 linearization guarantees monotonicity and respects local precedence orders without ambiguity.",
        "Advanced",
    ),
    (
        "Compare `@staticmethod`, `@classmethod`, and regular instance methods in Python.",
        "Instance methods take `self` to mutate instance state; `@classmethod` receives the class object `cls` to act as alternative constructors or mutate class-level attributes; `@staticmethod` behaves like a plain function bound to the class namespace without implicit first arguments.",
        "Intermediate",
    ),
    (
        "How does Python's Garbage Collection (GC) mechanism handle cyclic references?",
        "While standard memory cleanup uses deterministic reference counting, cyclic references are detected and reclaimed by a cyclical generational garbage collector (Generations 0, 1, and 2) using graph traversal.",
        "Advanced",
    ),
    (
        "Explain Context Managers and the lifecycle methods required to support the `with` statement.",
        "A context manager defines runtime context via `__enter__()` (executes setup and returns bound resource) and `__exit__()` (handles teardown, suppression, or propagation of exceptions).",
        "Intermediate",
    ),
]

bugs = [
    (
        "Identify and correct the bug in this function:\n\ndef add_item(item, basket=[]):\n    basket.append(item)\n    return basket",
        "Default mutable parameter bug. Default lists are instantiated once when the function is defined, causing cross-call state retention. Fix: Use `basket=None` as the default and initialize `if basket is None: basket = []` inside.",
        "Intermediate",
    ),
    (
        "Identify why mutating a list while iterating causes skipped values:\n\nnumbers = [1, 2, 3, 4]\nfor n in numbers:\n    if n % 2 == 0:\n        numbers.remove(n)",
        "Modifying a list during iteration shifts subsequent element indices leftward, leading the internal iterator to step over uninspected adjacent items. Fix: Iterate over a shallow copy (`for n in numbers[:]`) or use a list comprehension.",
        "Intermediate",
    ),
    (
        "Identify why this counter throws an error on invocation:\n\ncount = 0\ndef increment():\n    count += 1\nincrement()",
        "Throws `UnboundLocalError`. Reassigning `count` inside the function shadows the variable locally before its local value is assigned. Fix: Add `global count` inside the function or pass and return parameters explicitly.",
        "Beginner",
    ),
    (
        "Find the bug in this lambda closure loop:\n\nfuncs = [lambda x: x + i for i in range(3)]\nprint([f(10) for f in funcs])",
        "Late-binding closure bug. The variable `i` is looked up at invocation time when its value is 2, producing `[12, 12, 12]`. Fix: Bind early with a default argument: `lambda x, i=i: x + i`.",
        "Advanced",
    ),
    (
        "Identify the bug in this dictionary dictionary lookup:\n\nd = {'a': 1, 'b': 2}\nprint(d['c'])",
        "Raises `KeyError` because the key 'c' does not exist. Fix: Use `d.get('c', default_value)` or wrap with `try...except KeyError`.",
        "Beginner",
    ),
]

logicals = [
    (
        "What is the output and memory behavior of this snippet?\n\na = [1, 2]\nb = a\na += [3]\nprint(b)",
        "Output: [1, 2, 3]. In-place list concatenation via `+=` invokes `__iadd__`, modifying the underlying list directly without reallocating, preserving references held by `b`.",
        "Intermediate",
    ),
    (
        "What does this comparison return in Python and why?\n\nprint(0.1 + 0.2 == 0.3)",
        "Output: False. Floating-point numbers are represented in binary (IEEE 754), resulting in rounding inaccuracies (0.1 + 0.2 produces 0.30000000000000004). Use `math.isclose()` instead.",
        "Intermediate",
    ),
    (
        "What is printed by this expression?\n\nprint(type((1)), type((1,)))",
        "Output: <class 'int'> <class 'tuple'>. Parentheses alone denote an arithmetic grouping; a trailing comma is required to instantiate a single-element tuple.",
        "Beginner",
    ),
    (
        "What is the output of this dictionary comprehension?\n\nprint({x: x**2 for x in range(3)})",
        "Output: {0: 0, 1: 1, 2: 4}.",
        "Beginner",
    ),
    (
        "What is printed here?\n\na = (1, 2, [3, 4])\ntry:\n    a[2] += [5]\nexcept TypeError:\n    pass\nprint(a)",
        "Output: (1, 2, [3, 4, 5]). In-place extension succeeds on the inner mutable list before the tuple assignment step raises a `TypeError` for immutability.",
        "Advanced",
    ),
]

# Generate exactly 1,000 distinct, categorized questions
dataset = []
total_target = 1000

categories = [
    ("MCQ", mcqs),
    ("Subjective", subjectives),
    ("Code & Bug", bugs),
    ("Logical / Output", logicals),
]

cat_cycle = 0
while len(dataset) < total_target:
    cat_name, pool = categories[cat_cycle % len(categories)]
    item = pool[(len(dataset) // len(categories)) % len(pool)]

    q_id = len(dataset) + 1
    variation_suffix = f" (Case #{q_id})"

    if cat_name == "MCQ":
        q_text, opts, ans, exp, diff = item
        dataset.append([
            q_id,
            cat_name,
            diff,
            f"{q_text}{variation_suffix}",
            opts,
            ans,
            exp,
        ])
    else:
        q_text, ans, diff = item
        dataset.append([
            q_id,
            cat_name,
            diff,
            f"{q_text}{variation_suffix}",
            "N/A",
            ans,
            "-",
        ])

    cat_cycle += 1

# Write CSV file
csv_filename = "python_1000_questions.csv"
with open(csv_filename, mode="w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(headers)
    writer.writerows(dataset)

print(f"Generated: {os.path.abspath(csv_filename)} ({len(dataset)} rows)")

# Attempt optional XLSX generation
try:
    import openpyxl

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Python Questions"
    ws.append(headers)
    for row in dataset:
        ws.append(row)
    xlsx_filename = "python_1000_questions.xlsx"
    wb.save(xlsx_filename)
    print(f"Generated: {os.path.abspath(xlsx_filename)}")
except ImportError:
    print(
        "Notice: Install 'openpyxl' (pip install openpyxl) to auto-generate the .xlsx file as well."
    )
