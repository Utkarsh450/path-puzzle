package com.arrowpuzzle.game

/** Raw JNI surface of the C++ puzzle core (see app/src/main/cpp/jni_bridge.cpp for layouts). */
object Native {
    init {
        System.loadLibrary("arrowcore")
    }

    external fun create(level: Int, salt: Int): Long
    external fun destroy(handle: Long)
    external fun copy(handle: Long): Long
    external fun info(handle: Long): IntArray
    external fun arrow(handle: Long, id: Int): IntArray
    external fun features(handle: Long): IntArray
    external fun tap(handle: Long, id: Int, reverse: Boolean): IntArray
    external fun erase(handle: Long, id: Int): IntArray
    external fun tappable(handle: Long, id: Int): Boolean
    external fun hint(handle: Long): IntArray
    external fun remaining(handle: Long): Int
    external fun solvable(handle: Long): Boolean
}
