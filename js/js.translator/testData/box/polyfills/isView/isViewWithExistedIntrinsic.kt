// WITH_STDLIB
// FILE: main.js
this.ArrayBuffer = withMocks(ArrayBuffer, {
    isView(a) {
        return a != null && a.__proto__ != null && a.__proto__.__proto__ === Int8Array.prototype.__proto__;
    }
})

// FILE: main.kt
fun box(): String {
    val intArr = intArrayOf(5, 4, 3, 2, 1)
    val result = IntArray(5).apply { intArr.copyInto(this) }

    assertEquals(result.joinToString(","), intArr.joinToString(","))
    assertEquals(js("ArrayBuffer.isView.called"), true)

    return "OK"
}
