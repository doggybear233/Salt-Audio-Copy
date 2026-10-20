package com.saltrip.rip.ui

import java.io.DataInputStream
import java.io.InputStream

/**
 * 定位「CD 工具箱页里、位于 Column 内的那个子组件」。
 *
 * 为什么需要它：宿主页面 `androidx/compose/ui.ݩ` 自己有一个私有合成方法
 * `(VM, ColumnScope, Composer, int)`，即 `Column { … }` 的内容 lambda；该 lambda 里
 * 调用了两次 `com/xuncorp/voxzen/ui/component/ރ` 的 `Ϳ` / `Ԩ`。
 * 只要 hook 这两个调用之一（`inClass` 指页面类），插件内容就会成为 Column 的子项，
 * 从而真正参与页面排版（而不是像 hook 整页那样叠在 Box 上）。
 *
 * 宿主升级会改混淆名，所以这里不写死：运行时读取页面类的字节码常量池，
 * 找出「属于 com/xuncorp/voxzen/ui/component/ 且描述符为 (Modifier, Composer, II)V」的
 * 方法引用，按常量池顺序返回；写死的名字只作为兜底。
 *
 * 页面类本身也通过「`ComposableSingletons$AppNavigation_desktopKt` 调用、参数是带
 * StateFlow 的 ViewModel」这一特征确认不了，所以仍给出常量；失败时由 [PanelMount] 降级到底部坞。
 */
object HostTargets {

    /** 页面渲染器（宿主 1.19.0）。 */
    const val PAGE_CLASS = "androidx.compose.ui.\u0769"
    const val PAGE_METHOD = "\u037f"
    const val PAGE_CALLER =
        "com.xuncorp.voxzen.ui.navigation.ComposableSingletons\$AppNavigation_desktopKt"
    const val PAGE_PARAM = "androidx.compose.ui.\u0998"

    /** 兜底：实测这两个方法就是页面 Column 里的两次调用。 */
    private val FALLBACK = listOf(
        Target("com.xuncorp.voxzen.ui.component.\u0783", "\u0528"),
        Target("com.xuncorp.voxzen.ui.component.\u0783", "\u037f")
    )

    private const val COMPONENT_PREFIX = "com/xuncorp/voxzen/ui/component/"
    private const val COMPONENT_DESC =
        "(Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;II)V"

    data class Target(val owner: String, val method: String) {
        override fun toString(): String = "$owner.$method"
    }

    /** 候选顺序：先解析出来的，再兜底常量（去重）。 */
    fun candidates(): List<Target> {
        val found = runCatching { parsePageClass() }.getOrElse { emptyList() }
        return (found + FALLBACK).distinct()
    }

    /** 读页面类常量池，找子组件方法引用。失败返回空表，不抛。 */
    fun parsePageClass(): List<Target> {
        val res = HostTargets::class.java.classLoader
            ?.getResourceAsStream(PAGE_CLASS.replace('.', '/') + ".class") ?: return emptyList()
        return res.use { parse(it) }
    }

    fun parse(input: InputStream): List<Target> {
        val cp = ConstantPool.read(DataInputStream(input))
        val out = LinkedHashSet<Target>()
        for (ref in cp.methodRefs) {
            val owner = cp.className(ref.ownerIndex) ?: continue
            if (!owner.startsWith(COMPONENT_PREFIX)) continue
            val nameAndType = cp.nameAndType(ref.nameAndTypeIndex) ?: continue
            if (nameAndType.second != COMPONENT_DESC) continue
            out += Target(owner, nameAndType.first)
        }
        return out.toList()
    }

    /** 只够用的常量池读取器：utf8 / class / name&type / methodref。 */
    private class ConstantPool(
        val utf8: Map<Int, String>,
        val classIndices: Map<Int, Int>,
        val nameAndType: Map<Int, Pair<Int, Int>>,
        val methodRefs: List<Ref>
    ) {
        data class Ref(val ownerIndex: Int, val nameAndTypeIndex: Int)

        fun className(index: Int): String? =
            classIndices[index]?.let { utf8[it] }

        fun nameAndType(index: Int): Pair<String, String>? {
            val (n, d) = nameAndType[index] ?: return null
            val name = utf8[n] ?: return null
            val desc = utf8[d] ?: return null
            return name to desc
        }

        companion object {
            fun read(input: DataInputStream): ConstantPool {
                require(input.readInt() == -0x35014542) { "not a class file" } // 0xCAFEBABE
                input.readUnsignedShort() // minor
                input.readUnsignedShort() // major
                val count = input.readUnsignedShort()
                val utf8 = HashMap<Int, String>()
                val classes = HashMap<Int, Int>()
                val nats = HashMap<Int, Pair<Int, Int>>()
                val refs = ArrayList<Ref>()
                var i = 1
                while (i < count) {
                    when (val tag = input.readUnsignedByte()) {
                        1 -> utf8[i] = input.readUTF()
                        3, 4 -> input.skipNBytes(4)
                        5, 6 -> {
                            input.skipNBytes(8)
                            i++ // 长整型占两格
                        }
                        7 -> classes[i] = input.readUnsignedShort()
                        8 -> input.skipNBytes(2)
                        9, 10, 11 -> {
                            val owner = input.readUnsignedShort()
                            val nat = input.readUnsignedShort()
                            if (tag == 10) refs += Ref(owner, nat) // 只关心 Methodref
                        }
                        12 -> nats[i] = input.readUnsignedShort() to input.readUnsignedShort()
                        15 -> input.skipNBytes(3)
                        16 -> input.skipNBytes(2)
                        17, 18 -> input.skipNBytes(4)
                        19, 20 -> input.skipNBytes(2)
                        else -> error("unsupported constant pool tag $tag at #$i")
                    }
                    i++
                }
                return ConstantPool(utf8, classes, nats, refs)
            }
        }
    }
}
