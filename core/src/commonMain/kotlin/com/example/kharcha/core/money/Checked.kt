package com.example.kharcha.core.money

// Overflow-checked Long arithmetic for common code (java.lang.Math is JVM-only).
// Money must fail loudly rather than silently wrap around.

internal fun addExact(a: Long, b: Long): Long {
    val r = a + b
    if (((a xor r) and (b xor r)) < 0) throw ArithmeticException("long overflow")
    return r
}

internal fun subtractExact(a: Long, b: Long): Long {
    val r = a - b
    if (((a xor b) and (a xor r)) < 0) throw ArithmeticException("long overflow")
    return r
}

internal fun negateExact(a: Long): Long {
    if (a == Long.MIN_VALUE) throw ArithmeticException("long overflow")
    return -a
}

internal fun multiplyExact(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val r = a * b
    if (r / b != a || (a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) {
        throw ArithmeticException("long overflow")
    }
    return r
}
