#!/usr/bin/env python3
"""Rewrite direct AST record construction in a test source onto a published factory.

The published builders (AstBuilders/Expr/ScriptBuilders) spell a record's modifier by
*name* where the record carries it as a *value*: `elementOf`/`notElementOf` for
ElementOfPredicate's `negated`, `asc`/`desc` for a SortSpecification's direction,
`limit(count, input)` for a LimitNode whose offset is `Optional.empty()`. No such
factory is a permutation of the constructor, so the #779 rewriter — which only ever
substituted a pure permutation — could not touch them. They are nonetheless mechanical,
because the value at the call site is almost always the literal the factory names.

  new ElementOfPredicate(attr("a"), set, false)  ->  elementOf(attr("a"), set)
  new SortSpecification("amount", SortDirection.DESC)  ->  desc("amount")
  new LimitNode(Optional.empty(), 5, input)  ->  limit(5, input)

Two rules carried over from #779, both load-bearing:

  * The mapping is DERIVED FROM THE FACTORY BODIES, never typed by hand. A body is
    `return new K(a, CONST, b)`, so the constants are read off it and the surviving
    arguments give the permutation from constructor order to factory order. A factory
    that changes its constant changes this tool with it.
  * The AUTHOR'S LINE STRUCTURE IS PRESERVED. Each argument is carried across as its
    own source slice, newlines and indentation intact; continuation lines are shifted
    by the width the call prefix lost, so a multi-line call stays aligned.

The compiler is the backstop, as it was in #779: `asc` takes a String and `sortKey` an
Operand, so choosing the wrong one cannot reach a green build. What the compiler cannot
see is a swap of two same-typed arguments, and this tool never reorders same-typed
arguments — it only relocates them by the derived mapping.

Usage:
    python3 tools/ast-builders/literal_rewrite.py --dry-run relix-cost
    python3 tools/ast-builders/literal_rewrite.py --write relix-cost
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

FACTORY_SOURCES = [
    ("com.darkcollective.relix.ast.AstBuilders",
     "relix-ast/src/main/java/com/darkcollective/relix/ast/AstBuilders.java"),
    ("com.darkcollective.relix.ast.Expr",
     "relix-ast/src/main/java/com/darkcollective/relix/ast/Expr.java"),
    ("com.darkcollective.relix.lang.ast.ScriptBuilders",
     "relix-lang-ast/src/main/java/com/darkcollective/relix/lang/ast/ScriptBuilders.java"),
]

SIGNATURE = re.compile(r'public static ([A-Za-z0-9_.<>,\[\] ?]+?) ([a-zA-Z0-9_]+)\(([^)]*)\)\s*\{')


# --------------------------------------------------------------------------- parsing

def split_top_level(text, keep_raw=False):
    """Split on commas outside brackets and string literals."""
    parts, depth, cur, quote, i = [], 0, '', None, 0
    while i < len(text):
        ch = text[i]
        if quote:
            cur += ch
            if ch == '\\':
                cur += text[i + 1]
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in '"\'':
            quote = ch
            cur += ch
            i += 1
            continue
        if ch in '<([{':
            depth += 1
        elif ch in '>)]}':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
        i += 1
    if cur.strip():
        parts.append(cur)
    return parts if keep_raw else [p.strip() for p in parts]


def balanced_end(text, open_paren):
    """Index of the ')' closing the '(' at `open_paren`."""
    depth, quote, i = 0, None, open_paren
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == '\\':
                i += 2
                continue
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in '"\'':
            quote = ch
            i += 1
            continue
        if ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError('unbalanced call')


def comment_mask(text):
    """True at every index inside a comment or a string literal."""
    mask = [False] * len(text)
    i, n = 0, len(text)
    while i < n:
        two = text[i:i + 2]
        if two == '//':
            j = text.find('\n', i)
            j = n if j < 0 else j
            for k in range(i, j):
                mask[k] = True
            i = j
        elif two == '/*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                mask[k] = True
            i = j
        elif text[i] in '"\'':
            quote, j = text[i], i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                j += 1
            for k in range(i, min(j, n)):
                mask[k] = True
            i = j
        else:
            i += 1
    return mask


# ------------------------------------------------------------------- factory surface

SPLICE = re.compile(r'List\.of\((\w+)\)$')
WRAP = re.compile(r'Optional\.of\((\w+)\)$')
NESTED = re.compile(r'new (\w+)\(([\w,\s]*)\)$')


class Factory:
    def __init__(self, owner, name, params, body_args, target, returns):
        self.owner = owner
        self.name = name
        self.returns = returns
        self.param_names = [p.split()[-1] for p in params]
        self.param_types = [' '.join(p.split()[:-1]) for p in params]
        self.body_args = body_args
        self.target = target
        self.varargs = next((p.split()[-1] for p in params if '...' in p), None)
        # the constructor argument this factory's varargs parameter is packed into
        self.splice = next((i for i, a in enumerate(body_args)
                            if self.varargs and SPLICE.fullmatch(a)
                            and SPLICE.fullmatch(a).group(1) == self.varargs), None)
        # a component the factory wraps for its caller: `Optional.of(alias)`
        self.wrapped = {i: WRAP.fullmatch(a).group(1) for i, a in enumerate(body_args)
                        if WRAP.fullmatch(a) and WRAP.fullmatch(a).group(1) in self.param_names}
        # a component the factory wraps in another record: `new NamedQueryTarget(name)`
        self.nested = {}
        for i, a in enumerate(body_args):
            m = NESTED.fullmatch(a)
            if m and m.group(2).strip() and all(
                    n.strip() in self.param_names for n in m.group(2).split(',')):
                self.nested[i] = (m.group(1), [n.strip() for n in m.group(2).split(',')])
        self.constants = [(i, a) for i, a in enumerate(body_args)
                          if a not in self.param_names and a != 'SourceLocation.UNKNOWN'
                          and i != self.splice and i not in self.wrapped
                          and i not in self.nested]
        # constructor position of each parameter, in the factory's own argument order
        positions = {}
        for i, a in enumerate(body_args):
            if a in self.param_names:
                positions[a] = i
            elif i in self.wrapped:
                positions[self.wrapped[i]] = i
            elif i in self.nested:
                for offset, name in enumerate(self.nested[i][1]):
                    positions[name] = (i, offset)
        self.from_ctor = [positions[p] for p in self.param_names if p != self.varargs]

    @property
    def arity(self):
        return len(self.param_names)

    @property
    def fixed_types(self):
        return [t for p, t in zip(self.param_names, self.param_types) if p != self.varargs]

    def __repr__(self):
        return f'{self.name}/{self.arity}'


def inline_delegation(body):
    """`return K.simple(expr);` rewritten as the construction that static factory performs.

    Some factories delegate to the record's own static factory rather than construct —
    `projected(expr)` is `ProjectedAttribute.simple(expression)`, `agg(op, attr)` is
    `AggregateFunction.simple(...)`. Read literally, those factories build nothing and
    the rule never matches the 39 call sites that write the construction out in full.
    Inlining one level makes them ordinary.
    """
    delegation = re.match(r'return ([A-Z]\w+)\.(\w+)\((.*)\);$', body, re.S)
    if not delegation:
        return body
    record, method, arguments = delegation.groups()
    for source in ROOT.glob(f'relix-*/src/main/java/**/{record}.java'):
        text = source.read_text()
        for m in SIGNATURE.finditer(text):
            if m.group(2) != method or m.group(1).strip() != record:
                continue
            names = [p.split()[-1] for p in split_top_level(m.group(3))]
            passed = split_top_level(arguments)
            if len(names) != len(passed):
                continue
            inner = balanced_body(text, m.end() - 1)
            # A guard clause may precede the construction — `aliased` rejects a blank
            # alias first — so match the body's one and only construction, not its whole
            # text. Two of them would be a branch, and a branch is not a substitution.
            if inner.count(f'new {record}(') != 1:
                continue
            construction = re.search(r'return new %s\((.*)\);$' % record, inner, re.S)
            if not construction:
                continue
            bound = dict(zip(names, passed))
            return 'return new %s(%s);' % (record, ', '.join(
                bound.get(a, a) for a in split_top_level(construction.group(1))))
    return body


def balanced_body(text, open_brace):
    depth, j = 0, open_brace
    while j < len(text):
        if text[j] == '{':
            depth += 1
        elif text[j] == '}':
            depth -= 1
            if depth == 0:
                return text[open_brace + 1:j].strip()
        j += 1
    return ''


def load_signatures():
    """`name -> return type` for every published factory, whatever its body.

    The type environment needs all of them, not only the ones this rule can rewrite:
    `func(String, Operand...)` builds its list rather than permuting its parameters and
    is no rewrite candidate, but `desc(func("to_timestamp", attr("at")))` is still
    wrong — `desc` takes a column name — and nothing but the signature says so.
    """
    signatures = {}
    for _, path in FACTORY_SOURCES:
        for m in SIGNATURE.finditer((ROOT / path).read_text()):
            signatures[m.group(2)] = m.group(1).strip()
    return signatures


def load_factories():
    """Every factory whose body is `return new K(...)`, keyed by the record it builds."""
    factories = {}
    for owner, path in FACTORY_SOURCES:
        text = (ROOT / path).read_text()
        for m in SIGNATURE.finditer(text):
            name, params = m.group(2), split_top_level(m.group(3))
            open_brace = m.end() - 1
            depth, j = 0, open_brace
            while j < len(text):
                if text[j] == '{':
                    depth += 1
                elif text[j] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            body = text[open_brace + 1:j].strip()
            body = inline_delegation(body)
            construction = re.match(r'return new ([A-Za-z0-9_]+)\((.*)\);$', body, re.S)
            if not construction:
                continue
            args = split_top_level(construction.group(2))
            names = [p.split()[-1] for p in params]
            packed = [SPLICE.fullmatch(a).group(1) for a in args if SPLICE.fullmatch(a)]
            boxed = [WRAP.fullmatch(a).group(1) for a in args
                     if WRAP.fullmatch(a) and WRAP.fullmatch(a).group(1) in names]
            inside = [n.strip() for a in args if NESTED.fullmatch(a)
                      for n in NESTED.fullmatch(a).group(2).split(',')
                      if n.strip() in names]
            surviving = [a for a in args if a in names] + packed + boxed + inside
            if sorted(surviving) != sorted(names):
                continue                      # body reshapes an argument; not a substitution
            factories.setdefault(construction.group(1), []).append(
                Factory(owner, name, params, args, construction.group(1),
                        m.group(1).strip()))
    return factories


# ---------------------------------------------------------------------- the match

SCALARS = {'String', 'long', 'double', 'boolean', 'int', 'List', 'Optional', 'OptionalLong'}


def type_environment(text, published):
    """What the names in this file evaluate to — enough to tell `asc` from `sortKey`.

    Two maps, and keeping them apart is not pedantry: a *variable* may be named after a
    factory. `ImportGraphTest`'s `String path` and `AstBuilders.path` (the PathNode
    factory) are the same identifier in different namespaces, and folding them together
    types the argument as a `PathNode` and silently skips a rewrite that was fine.

    So an identifier in argument position is looked up as a value — a parameter or a
    local — and only `name(...)` is looked up as a call. Anything else stays unknown,
    and an unknown argument is let through for the compiler to judge, which is the right
    asymmetry: a wrong guess here only ever skips a rewrite that was safe.
    """
    code = ''.join(' ' if inside else ch for ch, inside in zip(text, comment_mask(text)))
    calls = dict(published)
    for m in re.finditer(r'(?:^|[\s;{}])(?:(?:public|private|protected|static|final)\s+)*'
                         r'([\w.<>\[\]]+)\s+(\w+)\s*\([^;{)]*\)\s*\{', code):
        calls[m.group(2)] = m.group(1)
    seen = {}
    for m in re.finditer(r'(?:^|[\s(,])([A-Z]\w+|String|long|int|boolean|double)'
                         r'(?:<[^<>]*>)?(?:\.\.\.|\[\])?\s+(\w+)\s*[=,;):]', code):
        seen.setdefault(m.group(2), set()).add(m.group(1))
    # A name declared with two types in one file says nothing about either — scope is
    # per method and this is not, so `String right` in one test and `RelNode right` in
    # another must leave the argument unknown rather than pick whichever came first.
    values = {name: next(iter(types)) for name, types in seen.items() if len(types) == 1}
    return calls, values


def argument_type(arg, environment=None):
    arg = arg.strip()
    calls, values = environment or ({}, {})
    call = re.fullmatch(r'([A-Za-z_]\w*)\s*\(.*\)', arg, re.S)
    if call and call.group(1) in calls:
        return re.sub(r'<.*>', '', calls[call.group(1)])
    if arg in values:
        return values[arg]
    if arg.startswith('"'):
        return 'String'
    if re.fullmatch(r'-?\d+[lL]?', arg):
        return 'long'
    if re.fullmatch(r'-?\d+\.\d+', arg):
        return 'double'
    if arg in ('true', 'false'):
        return 'boolean'
    if arg.startswith(('List.of(', 'List.copyOf(')) or arg.endswith('.toList()'):
        return 'List'
    if arg.startswith('Optional.'):
        return 'Optional'
    if arg.startswith('OptionalLong.'):
        return 'OptionalLong'
    constructed = re.match(r'new ([\w.]+)\s*\(', arg)   # a qualified name is still a type
    return constructed.group(1).rsplit('.', 1)[-1] if constructed else None


def compatible(arg, declared, environment=None):
    """Crude, and deliberately so — the compiler is the real check."""
    actual = argument_type(arg, environment)
    if actual is None:
        return True
    declared = re.sub(r'<.*>', '', declared.strip().replace('final ', ''))
    if actual == declared or declared == 'Object':
        return True
    if actual in ('long', 'double') and declared in ('long', 'double', 'int', 'Long', 'Double'):
        return True
    if actual == 'boolean' and declared in ('boolean', 'Boolean'):
        return True
    return actual not in SCALARS and declared not in SCALARS


WITNESS = re.compile(r'\.<[^<>]*>')
QUALIFIED = re.compile(r'\b(?:java\.util|java\.lang)\.')


def same_constant(written, expected):
    """`SortDirection.ASC` matches a statically imported `ASC`; whitespace is ignored."""
    written, expected = ' '.join(written.split()), ' '.join(expected.split())
    written = WITNESS.sub('.', written)        # `Optional.<String>empty()` is `Optional.empty()`
    written = QUALIFIED.sub('', written)       # and `java.util.Optional.empty()` is too
    expected = QUALIFIED.sub('', expected)
    if written == expected:
        return True
    return '.' in expected and written == expected.rsplit('.', 1)[1]


def match(factory, args, environment=None):
    """The factory's arguments in call order, or None if this call is not that factory."""
    if len(args) != len(factory.body_args):
        return None
    for position, constant in factory.constants:
        if not same_constant(args[position], constant):
            return None
    if factory.splice is not None and list_literal(args[factory.splice]) is None:
        return None
    if any(unwrapped(args[position]) is None for position in factory.wrapped):
        return None
    for position, (inner, names) in factory.nested.items():
        held = nested_args(args[position], inner)
        if held is None or len(held) != len(names):
            return None
    kept = [pick(factory, args, p) for p in factory.from_ctor]
    if any(not compatible(a, t, environment)
           for a, t in zip(kept, factory.fixed_types)):
        return None
    return kept


def pick(factory, args, position):
    """The call argument a factory parameter is bound to, unwrapping as the body does."""
    if isinstance(position, tuple):
        outer, offset = position
        return nested_args(args[outer], factory.nested[outer][0])[offset]
    if position in factory.wrapped:
        return unwrapped(args[position])
    return args[position]


def nested_args(arg, inner):
    """The arguments of a `new Inner(…)` call, or None if the argument is not one.

    A factory may build a wrapper for its caller: `query(name)` is
    `new QueryStatement(new NamedQueryTarget(name))`, so a call that writes the wrapper
    out in full is that factory with the inner arguments passed straight through.
    """
    arg = arg.strip()
    prefix = f'new {inner}('
    if not arg.startswith(prefix) or not arg.endswith(')'):
        return None
    return split_top_level(arg[len(prefix):-1], keep_raw=True)


def unwrapped(arg):
    """What an `Optional.of(…)` argument holds, or None if it is anything else.

    A factory that boxes a component for its caller — `projected(operand, alias)` for
    `new ProjectedAttribute(operand, Optional.of(alias))` — matches only a call that
    boxes it at the call site. An `Optional` arriving as a variable cannot be unboxed.
    """
    arg = QUALIFIED.sub('', arg.strip())
    if not arg.startswith('Optional.of(') or not arg.endswith(')'):
        return None
    inner = split_top_level(arg[len('Optional.of('):-1], keep_raw=True)
    return inner[0] if len(inner) == 1 else None


def list_literal(arg):
    """The elements of a `List.of(…)` argument, or None if it is anything else.

    A varargs factory packs its own list, so it matches only a call that hands the
    constructor a list written out at the call site — `List.of(a, b)` becomes `f(a, b)`.
    A list arriving as a variable, a stream or a `List.copyOf` cannot be unpacked, and
    is left for the constructor.
    """
    arg = arg.strip()
    if not arg.startswith('List.of(') or not arg.endswith(')'):
        return None
    return split_top_level(arg[len('List.of('):-1], keep_raw=True)


# ------------------------------------------------------------------------ rewriting

DECLARATION = re.compile(
    r'(?:^|[\s;{}])(?:[\w.<>\[\]]+\s+)+(\w+)\s*\([^;{)]*\)\s*(?:throws [\w, .]+)?\{')


class Shadowing:
    """Which factory names are hidden at a given point in the file.

    A method declared in a class hides *every* inherited or statically imported method
    of that name, whatever its signature — `@Test void limit()` hides
    `limit(long, RelNode)`, and javac reports only "cannot be applied to given types".
    Two things make this worth modelling properly rather than per file:

    * **Scope is the class, not the file.** `CostEstimatorTest` declares a
      `rel(long, Map)` building *statistics* inside one `@Nested` class; read per file
      that hides the `rel` factory at 120 call sites in the outer class that it does
      not actually hide, and each would be qualified for nothing.
    * **Scope reaches the superclass.** `ProcessorTestSupport.num`/`str` build a row
      *value*, so `num("5")` in a class extending it is not the operand factory of the
      same name — and nothing in the file itself says so.
    """

    def __init__(self, text):
        code = ''.join(' ' if inside else ch
                       for ch, inside in zip(text, comment_mask(text)))
        self.classes = []
        for m in re.finditer(r'\bclass (\w+)(?:\s+extends\s+([\w.]+))?[^{;]*\{', code):
            try:
                self.classes.append((m.start(), balanced_end(code, code.index('{', m.end() - 1)),
                                     m.group(2), set()))
            except ValueError:
                continue
        self.classes.sort(key=lambda c: c[0])
        for m in DECLARATION.finditer(code):
            innermost = None
            for candidate in self.classes:
                if candidate[0] <= m.start() < candidate[1]:
                    innermost = candidate
            if innermost is not None:
                innermost[3].add(m.group(1))
        self.cache = {}

    def at(self, index):
        """`name -> the classes that declare it` in scope at `index`.

        Not a bare set, because inheriting a factory is not the same as hiding one.
        `ParserTestSupport extends AstBuilders`, so a test in that hierarchy reaches
        `sessionize(…)` unqualified — but the *name* `duration` it inherits is
        `AstBuilders.duration(String)`, and that hides the static-imported
        `Expr.duration(Duration)` outright rather than overloading it. The declaring
        class is what tells the two apart.
        """
        declared = {}
        for start, end, superclass, methods in self.classes:
            if start <= index < end:
                for name in methods:
                    declared.setdefault(name, set()).add('<this>')
                if superclass:
                    for name, owners in inherited_names(superclass).items():
                        declared.setdefault(name, set()).update(owners)
        return declared


def inherited_names(class_name):
    """`name -> declaring classes` for a superclass and everything above it.

    Main sources are searched as well as test ones: the chain a test support class sits
    on usually ends at `AstBuilders` itself, and stopping at the module's test tree
    loses exactly the link that decides whether a name is inherited or hidden.
    """
    class_name = class_name.rsplit('.', 1)[-1]     # a superclass may be named in full
    if class_name in _INHERITED:
        return _INHERITED[class_name]
    _INHERITED[class_name] = {}             # guards a cycle while resolving
    declared = {}
    for source in ROOT.glob(f'relix-*/src/*/java/**/{class_name}.java'):
        text = source.read_text()
        code = ''.join(' ' if inside else ch
                       for ch, inside in zip(text, comment_mask(text)))
        for m in DECLARATION.finditer(code):
            declared.setdefault(m.group(1), set()).add(class_name)
        for m in re.finditer(r'\bclass \w+ extends ([\w.]+)', code):
            for name, owners in inherited_names(m.group(1)).items():
                declared.setdefault(name, set()).update(owners)
    _INHERITED[class_name] = declared
    return declared


_INHERITED = {}


def rewrite(source, text, factories, surface):
    """Rewrite every matching construction in `text`. Returns (text, [(kind, factory)])."""
    applied = []
    environment = type_environment(text, surface)
    while True:
        # Rebuilt per pass: a rewrite shortens the text, so class ranges taken before
        # it no longer say which class a later call site sits in.
        hidden = Shadowing(text)
        mask = comment_mask(text)
        kinds = '|'.join(sorted(factories))
        for m in re.finditer(r'\bnew (' + kinds + r')\s*\(', text):
            if mask[m.start()]:
                continue
            kind = m.group(1)
            open_paren = m.end() - 1
            try:
                close = balanced_end(text, open_paren)
            except ValueError:
                continue
            raw = split_top_level(text[open_paren + 1:close], keep_raw=True)
            args = [r.strip() for r in raw]
            for factory in factories[kind]:
                if not reachable(source, factory.owner):
                    continue
                kept_raw = match_raw(factory, args, raw, environment)
                if kept_raw is None:
                    continue
                owner = factory.owner.rsplit('.', 1)[1]
                declared = hidden.at(m.start()).get(factory.name, set())
                qualifier = owner + '.' if declared - {owner} else ''
                replacement = render(factory, kept_raw, qualifier,
                                     lost=len(f'new {kind}')
                                     - len(qualifier + factory.name),
                                     paren=column(text, open_paren))
                text = text[:m.start()] + replacement + text[close + 1:]
                applied.append((kind, factory.name, bool(qualifier)))
                break
            else:
                continue
            break                              # restart: offsets have moved
        else:
            return text, applied


def match_raw(factory, args, raw, environment):
    """The surviving argument slices, in factory order, with the line breaks kept.

    A dropped argument takes its own leading newline with it, which would silently join
    the line that followed onto the one before — the author wrote
    `new DownsampleNode(a, b, c,\n        List.of(), OptionalLong.empty(), input)` and
    would get all of `downsample(a, b, c, input)` on one line. So a dropped slice hands
    its leading whitespace to the next surviving one.
    """
    kept = match(factory, args, environment)
    if kept is None:
        return None
    slices = list(raw)
    spliced = list_literal(args[factory.splice]) if factory.splice is not None else []
    spliced = [] if spliced is None else spliced
    outer = [p[0] if isinstance(p, tuple) else p for p in factory.from_ctor]
    if outer == sorted(outer):                          # no reordering across lines
        surviving = set(factory.from_ctor)
        for position, slice_ in enumerate(slices):
            if position in surviving or not slice_.startswith('\n'):
                continue
            following = next((q for q in range(position + 1, len(slices))
                              if q in surviving), None)
            if following is not None and not slices[following].startswith('\n'):
                lead = slice_[:len(slice_) - len(slice_.lstrip(' \n'))]
                slices[following] = lead + slices[following].lstrip(' ')
    return [pick(factory, slices, p) for p in factory.from_ctor] + spliced


def column(text, index):
    """The 0-based column `index` sits at."""
    return index - (text.rfind('\n', 0, index) + 1)


def render(factory, kept_raw, qualifier, lost, paren):
    """`name(<args>)`, each argument carried across verbatim, continuations re-aligned.

    A continuation line moves only if it was aligned to the call's own parenthesis —
    that column has shifted left by `lost`, so the alignment has to follow it. A line
    indented as a block instead (the common `var x = new K(a,\n        b)`, whose
    continuation sits *left* of the parenthesis) is the author's paragraph shape rather
    than an alignment, and is left exactly where it was.
    """
    body = ','.join(kept_raw)
    if body[:1] in (' ', '\n'):
        body = body.lstrip(' ')                # a dropped leading argument left a gap
    if lost > 0:
        head, *tail = body.split('\n')
        body = '\n'.join([head] + [dedent(line, lost, paren) for line in tail])
    return f'{qualifier}{factory.name}({body})'


def dedent(line, width, paren):
    indent = len(line) - len(line.lstrip(' '))
    return line[min(indent, width):] if indent > paren else line


# ----------------------------------------------------------------------------- main

BASELINE = 'tools/ast-builders/baseline.tsv'


def record_kinds():
    """Every public record in the two AST modules — the ratchet's own definition."""
    kinds = set()
    for directory in ('relix-ast/src/main/java/com/darkcollective/relix/ast',
                      'relix-lang-ast/src/main/java/com/darkcollective/relix/lang/ast'):
        for source in (ROOT / directory).glob('*.java'):
            kinds |= set(re.findall(r'public record (\w+)', source.read_text()))
    return kinds


def constructions(text, kinds):
    code = []
    in_block = False
    for line in text.split('\n'):
        stripped = line.strip()
        commented = (in_block or stripped.startswith('//')
                     or stripped.startswith('/*') or stripped.startswith('*'))
        if stripped.startswith('/*') and '*/' not in stripped:
            in_block = True
        if in_block and stripped.endswith('*/'):
            in_block = False
        code.append('' if commented else line)
    pattern = re.compile(r'\bnew (' + '|'.join(sorted(kinds)) + r')\s*\(')
    return len(pattern.findall('\n'.join(code)))


def lower_baseline(rewritten):
    """Tighten each rewritten file's row, or delete it.

    #780's Method 5: the ratchet fails a file that is *below* its budget, so lowering
    the row belongs in the same commit as the migration — which makes it the tool's
    job rather than something to remember.
    """
    baseline = ROOT / BASELINE
    kinds, rows, report = record_kinds(), [], []
    for line in baseline.read_text().split('\n'):
        if not line.strip() or line.startswith('#'):
            rows.append(line)
            continue
        budget, path = line.split('\t')
        if rewritten is not None and path not in rewritten:
            rows.append(line)
            continue
        remaining = constructions((ROOT / path).read_text(), kinds)
        if remaining != int(budget):
            report.append(f'  {path.split("/")[-1]:44s} {budget:>4} -> {remaining}'
                          + ('  (row deleted)' if remaining == 0 else ''))
        if remaining:
            rows.append(f'{remaining}\t{path}')
    baseline.write_text('\n'.join(rows))
    return report


def reachable(source, owner):
    """Whether the module holding `source` can see the class `owner`.

    `ScriptBuilders` lives in `relix-lang-ast`, which sits *above* `relix-symbol` in the
    module graph — so `param(...)` is the right factory for a `ParameterDefinition` and
    is unreachable from the module that declares the record. Nothing in the call site
    says so, and the import the rewrite adds fails with "package does not exist".

    Read off the module's own sources rather than its build file: a package some file
    there already imports is one the module depends on, directly or transitively.
    """
    module = ROOT / str(source.relative_to(ROOT)).split('/')[0]
    package = owner.rsplit('.', 1)[0]
    if (module, package) not in _REACHABLE:
        _REACHABLE[(module, package)] = any(
            re.search(r'^import (?:static )?%s\.' % re.escape(package), java.read_text(), re.M)
            for java in module.rglob('src/*/java/**/*.java'))
    return _REACHABLE[(module, package)]


_REACHABLE = {}


def sources(targets):
    found = []
    for target in targets:
        path = ROOT / target
        if path.is_file():
            found.append(path)
            continue
        for source_set in ('src/test/java', 'src/testFixtures/java'):
            directory = path / source_set
            if directory.is_dir():
                found += sorted(directory.rglob('*.java'))
    return found


def ensure_import(text, factory_owners, qualified_owners):
    """Add the imports the rewrite now needs — the class, or its static members."""
    for owner in sorted(qualified_owners):
        if re.search(r'^import %s;$' % re.escape(owner), text, re.M):
            continue
        statement = f'import {owner};'
        plain = list(re.finditer(r'^import ([\w.]+);$', text, re.M))
        package = owner.rsplit('.', 1)[0]
        neighbours = [m for m in plain if m.group(1).startswith(package + '.')]
        if neighbours:                          # keep the file's own import grouping
            after = [m for m in neighbours if m.group(1) < owner]
            at, lead, trail = ((after[-1].end(), '\n', '') if after
                               else (neighbours[0].start(), '', '\n'))
        elif plain:
            at, lead, trail = plain[-1].end(), '\n', ''
        else:
            at, lead, trail = text.index(';') + 1, '\n\n', ''
        text = text[:at] + lead + statement + trail + text[at:]
    for owner, names in sorted(factory_owners.items()):
        if re.search(r'^import static %s\.\*;$' % re.escape(owner), text, re.M):
            continue
        # A file that imports the factories one by one keeps doing so: a wildcard added
        # beside single-name imports reads as a second, contradictory convention, and
        # the single ones do not cover a name the rewrite has just started using.
        single = list(re.finditer(r'^import static %s\.(\w+);$' % re.escape(owner),
                                  text, re.M))
        if single:
            for name in sorted(names - {m.group(1) for m in single}):
                statement = f'import static {owner}.{name};'
                single = list(re.finditer(r'^import static %s\.(\w+);$' % re.escape(owner),
                                          text, re.M))
                earlier = [m for m in single if m.group(1) < name]
                at, lead, trail = ((earlier[-1].end(), '\n', '') if earlier
                                   else (single[0].start(), '', '\n'))
                text = text[:at] + lead + statement + trail + text[at:]
            continue
        anchor = list(re.finditer(r'^import static .*;$', text, re.M))
        statement = f'import static {owner}.*;'
        if anchor:
            at = anchor[0].start()
            text = text[:at] + statement + '\n' + text[at:]
        else:
            plain = list(re.finditer(r'^import .*;$', text, re.M))
            at = plain[-1].end() if plain else text.index('\n', text.index('package'))
            text = text[:at] + '\n\n' + statement + text[at:]
    return text


def prune_imports(text, candidates):
    """Drop a single-type import whose name the rewrite has just made unused."""
    code = ''.join(' ' if inside else ch
                   for ch, inside in zip(text, comment_mask(text)))
    code = re.sub(r'^import .*;$', '', code, flags=re.M)
    kept = []
    for line in text.split('\n'):
        simple = re.fullmatch(r'import (?:static )?[\w.]*\.(\w+);', line.strip())
        if simple and simple.group(1) in candidates \
                and not re.search(r'\b%s\b' % simple.group(1), code):
            continue
        kept.append(line)
    return '\n'.join(kept)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('targets', nargs='*', help='modules or files to rewrite')
    parser.add_argument('--write', action='store_true', help='edit in place')
    parser.add_argument('--dry-run', action='store_true', help='report only (the default)')
    parser.add_argument('--sync-baseline', action='store_true',
                        help='tighten every row to what the tree now holds, and stop — '
                             'for a run whose rewrites landed across several invocations')
    parser.add_argument('--rule', choices=('literal', 'permutation', 'varargs', 'optional', 'nested', 'all'),
                        default='literal',
                        help="'literal' (the default) rewrites a call whose factory supplies "
                             "a constant; 'permutation' one whose factory is a pure "
                             "permutation of the constructor; 'varargs' one whose factory "
                             "packs its own list, unwrapping the List.of at the call site; "
                             "'optional' one that boxes a component the call site boxes too; "
                             "'nested' one that wraps its arguments in another record; "
                             "'all' does every rule")
    parser.add_argument('--except', dest='excluded', default='', metavar='KIND[,KIND]',
                        help='record kinds to leave alone — for a test whose subject IS '
                             'the record, where going through a factory weakens the claim')
    args = parser.parse_args()

    if args.sync_baseline:
        report = lower_baseline(None)
        print(f'{BASELINE} — {len(report)} rows tightened')
        print('\n'.join(report))
        return 0

    factories, signatures = load_factories(), load_signatures()
    excluded = {kind.strip() for kind in args.excluded.split(',') if kind.strip()}
    unknown = excluded - set(factories)
    if unknown:
        parser.error('not an AST record with a factory: ' + ', '.join(sorted(unknown)))
    wanted = {'literal': lambda f: f.constants and f.splice is None
                                   and not (f.wrapped or f.nested),
              'permutation': lambda f: not (f.constants or f.wrapped or f.nested)
                                       and f.splice is None,
              'varargs': lambda f: f.splice is not None,
              'optional': lambda f: bool(f.wrapped) and f.splice is None,
              'nested': lambda f: bool(f.nested) and f.splice is None,
              'all': lambda f: True}[args.rule]
    bearing = {k: [f for f in v if wanted(f)] for k, v in factories.items()
               if k not in excluded}
    bearing = {k: v for k, v in bearing.items() if v}
    print(f'{sum(len(v) for v in bearing.values())} {args.rule} factories '
          f'over {len(bearing)} record kinds\n')

    total, rewritten = 0, set()
    for source in sources(args.targets):
        before = source.read_text()
        after, applied = rewrite(source, before, bearing, signatures)
        if not applied:
            continue
        unqualified = {}
        for kind, name, q in applied:
            for factory in bearing[kind]:
                if factory.name == name and not q:
                    unqualified.setdefault(factory.owner, set()).add(name)
        after = ensure_import(
            after, unqualified,
            {f.owner for kind, name, q in applied
             for f in bearing[kind] if f.name == name and q})
        total += len(applied)
        counts = {}
        for kind, name, q in applied:
            label = name + (' (qualified)' if q else '')
            counts[label] = counts.get(label, 0) + 1
        summary = ', '.join(f'{n}×{name}' for name, n in sorted(counts.items()))
        print(f'{source.relative_to(ROOT)}  {len(applied):3d}  {summary}')
        names = {kind for kind, _, _ in applied}
        for kind, name, _ in applied:
            for factory in bearing[kind]:
                if factory.name == name:
                    names |= {constant.split('.')[0]
                              for _, constant in factory.constants}
        after = prune_imports(after, names)
        if args.write:
            source.write_text(after)
            rewritten.add(str(source.relative_to(ROOT)))

    print(f'\n{total} construction{"" if total == 1 else "s"} '
          f'{"rewritten" if args.write else "would be rewritten"}')
    if rewritten:
        report = lower_baseline(rewritten)
        print(f'\n{BASELINE} — {len(report)} row{"" if len(report) == 1 else "s"} tightened')
        print('\n'.join(report))
    return 0


if __name__ == '__main__':
    sys.exit(main())
