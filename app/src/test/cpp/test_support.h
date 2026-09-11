#pragma once

#include <cmath>
#include <cstdio>
#include <string>

// Shared by the graph and node test binaries. Deliberately tiny: a test framework here
// would be more code than the thing under test.

namespace testing {

inline int failures = 0;
inline int checks = 0;

inline void check(bool ok, const std::string &what) {
    ++checks;
    if (!ok) {
        ++failures;
        std::printf("  FAIL: %s\n", what.c_str());
    }
}

inline int report(const char *suite) {
    std::printf("\n%s: %d checks, %d failed\n", suite, checks, failures);
    std::fflush(stdout);
    return failures == 0 ? 0 : 1;
}

} // namespace testing
