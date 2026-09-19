package io.github.shrishaanth.codeatlas.parse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PythonParserTest {

    private final PythonParser parser = new PythonParser();

    @AfterEach
    void close() {
        parser.close();
    }

    @Test
    void extractsEveryImportForm() {
        String src = """
                import os
                import numpy as np, pandas.io
                from . import utils
                from ..core.hrp import allocate, rebalance as rb
                from typing import (
                    List,
                    Optional,
                )
                from pkg.mod import *
                import a.b.c as abc
                """;

        List<PyImport> imports = parser.parse("x/y.py", src).imports();

        assertThat(imports).containsExactly(
                new PyImport(1, "import os", false, 0, "os", List.of()),
                new PyImport(2, "import numpy as np, pandas.io", false, 0, "numpy", List.of()),
                new PyImport(2, "import numpy as np, pandas.io", false, 0, "pandas.io", List.of()),
                new PyImport(3, "from . import utils", true, 1, "", List.of("utils")),
                new PyImport(4, "from ..core.hrp import allocate, rebalance as rb", true, 2, "core.hrp",
                        List.of("allocate", "rebalance")),
                new PyImport(5, "from typing import ( List, Optional, )", true, 0, "typing",
                        List.of("List", "Optional")),
                new PyImport(9, "from pkg.mod import *", true, 0, "pkg.mod", List.of("*")),
                new PyImport(10, "import a.b.c as abc", false, 0, "a.b.c", List.of()));
    }

    @Test
    void findsNestedAndConditionalImports() {
        String src = """
                def load():
                    try:
                        import yaml
                    except ImportError:
                        yaml = None
                    if TYPE_CHECKING:
                        from app.models import User
                """;

        List<PyImport> imports = parser.parse("x.py", src).imports();

        assertThat(imports).extracting(PyImport::module).containsExactly("yaml", "app.models");
        assertThat(imports).extracting(PyImport::line).containsExactly(3, 7);
    }

    @Test
    void extractsSymbolsWithParentsAndLineRanges() {
        String src = """
                class Portfolio(object):
                    \"\"\"A portfolio.\"\"\"

                    def __init__(self, weights):
                        self.weights = weights

                    @property
                    def total(self):
                        def inner():
                            return 0
                        return sum(self.weights)


                async def fetch(url):
                    return url
                """;

        List<PySymbol> symbols = parser.parse("p.py", src).symbols();

        assertThat(symbols).containsExactly(
                new PySymbol("class", "Portfolio", 1, 11, null),
                new PySymbol("function", "__init__", 4, 5, "Portfolio"),
                new PySymbol("function", "total", 8, 11, "Portfolio"),
                new PySymbol("function", "inner", 9, 10, "total"),
                new PySymbol("function", "fetch", 14, 15, null));
    }

    @Test
    void usesByteOffsetsCorrectlyWithNonAsciiText() {
        String src = "# café ☕ — naïve\nNAME = 'Zoë'\nfrom données import café\n";

        ParsedPythonFile parsed = parser.parse("u.py", src);

        assertThat(parsed.imports()).containsExactly(
                new PyImport(3, "from données import café", true, 0, "données", List.of("café")));
        assertThat(parsed.firstErrorLine()).isZero();
    }

    @Test
    void reportsSyntaxErrorsButStillExtracts() {
        String src = "import os\n\ndef broken(:\n    pass\n\nimport sys\n";

        ParsedPythonFile parsed = parser.parse("b.py", src);

        assertThat(parsed.firstErrorLine()).isEqualTo(3);
        assertThat(parsed.imports()).extracting(PyImport::module).contains("os", "sys");
    }
}
