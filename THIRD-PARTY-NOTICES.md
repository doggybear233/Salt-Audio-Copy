# Third-party notices

Salt Audio Copy 本体以 MIT 许可发布（见 [LICENSE](LICENSE)）。
本文件说明随插件一起分发的第三方组件及其许可。

## FLAC 1.4.3（Windows x64）

插件包内 `classes/native/win64/` 包含下列文件，运行时解包到插件数据目录后调用：

| 文件 | 字节 | 用途 | 许可 |
| --- | ---: | --- | --- |
| `flac.exe` | 302,592 | FLAC 命令行编码器，作为**独立进程**执行 | GPL-2.0-or-later |
| `libFLAC.dll` | 446,976 | flac.exe 依赖的参考实现库 | BSD-3-Clause（Xiph） |
| `libFLAC++.dll` | 214,528 | flac.exe 依赖的 C++ 包装库 | BSD-3-Clause（Xiph） |

- 上游下载：<https://ftp.osuosl.org/pub/xiph/releases/flac/flac-1.4.3-win.zip>
  - 压缩包 SHA-256：`c4558cf79fc1365d1822f7945a20bdbf55f99642cee95a823d44f3a61fb748c6`
- 二进制 SHA-256：
  - `flac.exe`：`d80c96700d17ac08bf8cc517147a62dd12241544ced0315a1e5832c436d69264`
  - `libFLAC.dll`：`9f46d67fe71b59a8cfc83046c2802dca88ee92bfd97017330944db577ad58f4d`
  - `libFLAC++.dll`：`30c07adc9f6df46b5845e765bf6274c4c4f883ed5c838b6857dd830150b629f8`
- 对应源码（GPL 要求的完整对应源码获取方式）：
  - <https://github.com/xiph/flac/releases/tag/1.4.3>
  - <https://downloads.xiph.org/releases/flac/flac-1.4.3.tar.xz>
- 许可全文随包分发：`classes/native/win64/COPYING.GPL`（GPL-2.0）、
  `classes/native/win64/FLAC-COPYING.txt`（Xiph BSD-3-Clause）、
  `classes/native/win64/COPYING.FDL`（FLAC 手册所用 GNU FDL）、
  `classes/native/win64/AUTHORS`（上游贡献者名单）。

这些二进制文件未做任何修改（逐字节来自上述压缩包），插件不静态链接、
不修改它们，只以子进程方式调用 `flac.exe`。若你再次分发本插件，
需要同时保留上述 GPL 文本与源码获取途径。

## 未包含的组件

- 不使用 `metaflac.exe`（标签由插件自行写入）。
- 不使用 32 位（Win32）构建。
- 不包含任何受 GPL 影响的插件自有代码；`flac.exe` 是运行时外置进程。

## 字体与图标

界面使用宿主 Salt Player 的字体与主题，插件包内不含任何字体文件；
应用图标由本仓库自行绘制（MIT）。
