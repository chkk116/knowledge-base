package com.kb.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Paths

/**
 * 单实例锁。
 *
 * 为什么必须锁：第二个进程会和第一个抢同一份资源——
 *  - WebView2 的 user-data 目录（localStorage / initScript 都写在这里），并发写会互相踩，
 *    表现是「编辑页登录态时有时无」；
 *  - ConfigStore 的 config.json，两边各自读改写，后写的把先写的覆盖掉，
 *    表现是「刚改的设置在重启后自己变回去」。
 * 双击两次桌面图标是很常见的误操作，所以这里用 OS 级文件锁挡住。
 *
 * 实现细节：锁 `%USERPROFILE%\.kb-platform\.instance.lock`；进程崩溃时操作系统会自动释放，
 * 不会留下「假死锁」把用户永久挡在门外。
 */
object SingleInstance {
    @Volatile private var channel: FileChannel? = null
    @Volatile private var lock: FileLock? = null

    /** true = 本进程是唯一实例；false = 已有实例在运行 */
    fun acquire(): Boolean {
        return try {
            val dirPath = ConfigStore.dirPath().ifBlank {
                Paths.get(System.getProperty("user.home"), ".kb-platform").toString()
            }
            val dir = File(dirPath)
            dir.mkdirs()
            val ch = RandomAccessFile(File(dir, ".instance.lock"), "rw").channel
            val l = ch.tryLock()
            if (l == null) {
                ch.close()
                false
            } else {
                // 写入当前 pid，排查「谁占着锁」时直接看文件内容
                ch.truncate(0)
                ch.write(ByteBuffer.wrap(ProcessHandle.current().pid().toString().toByteArray()))
                ch.force(false)
                channel = ch
                lock = l
                true
            }
        } catch (e: Exception) {
            // 目录不可写 / 文件系统不支持锁（部分网络盘）：宁可允许双开，也不能让用户打不开
            true
        }
    }
}