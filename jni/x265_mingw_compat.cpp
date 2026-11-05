/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#if defined(_WIN32) && !defined(_MSC_VER)

/*
 * Kotlin/Native's MinGW target links an older libstdc++ than the MinGW
 * installation that compiles this archive. Two entry points that recent
 * libstdc++ headers emit are missing there:
 *
 *  - ios_base_library_init(), called by the std::__ioinit initializer that
 *    every <iostream> translation unit emits (GCC 15);
 *  - __throw_bad_array_new_length(), emitted by std::deque's growth path
 *    (GCC 11).
 *
 * They are weak, so a real libstdc++ definition wins wherever one exists.
 */

#include <cstdlib>

namespace std {

__attribute__((weak)) void ios_base_library_init() {}

__attribute__((weak, noreturn)) void __throw_bad_array_new_length() {
  abort();
}

}  // namespace std

#endif /* _WIN32 && !_MSC_VER */
