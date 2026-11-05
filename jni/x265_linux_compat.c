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

#if defined(__linux__)

/*
 * Ubuntu's glibc 2.38+ headers redirect strtol() to the C23 entry point
 * __isoc23_strtol() whenever _GNU_SOURCE is defined (which g++ does by
 * default). Kotlin/Native links Linux programs against its own sysroot
 * (glibc 2.19 for x64, 2.25 for arm64), where that symbol does not exist, so
 * the archive must provide it. Forwarding to the classic strtol keeps the
 * semantics x265 relies on (plain decimal parsing).
 *
 * The asm label is required: with these headers the plain name would call this
 * very function again.
 */
#include <stdarg.h>

extern long xky_libc_strtol(const char* nptr, char** endptr, int base) __asm__("strtol");
extern int xky_libc_vsscanf(const char* input, const char* format, va_list args) __asm__("vsscanf");

long __isoc23_strtol(const char* nptr, char** endptr, int base) {
  return xky_libc_strtol(nptr, endptr, base);
}

int __isoc23_sscanf(const char* input, const char* format, ...) {
  va_list args;
  va_start(args, format);
  const int result = xky_libc_vsscanf(input, format, args);
  va_end(args);
  return result;
}

#endif /* __linux__ */
