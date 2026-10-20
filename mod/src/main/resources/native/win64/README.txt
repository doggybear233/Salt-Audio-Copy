FLAC 1.4.3 (Windows x64) — bundled third-party binaries
=======================================================

What this directory contains
----------------------------
  flac.exe          302,592 bytes  FLAC command line encoder   GPL-2.0-or-later
  libFLAC.dll       446,976 bytes  FLAC reference library     BSD-3-Clause (COPYING.Xiph)
  libFLAC++.dll     214,528 bytes  FLAC C++ wrapper library   BSD-3-Clause (COPYING.Xiph)
  README.txt        this file
  COPYING.GPL       GNU General Public License version 2 (applies to flac.exe)
  COPYING.FDL       GNU Free Documentation License (applies to the FLAC manuals)
  FLAC-COPYING.txt  Xiph BSD 3-Clause license (applies to libFLAC and libFLAC++)
  AUTHORS           upstream list of contributors

Where it came from
------------------
  https://ftp.osuosl.org/pub/xiph/releases/flac/flac-1.4.3-win.zip
  (archive SHA-256 is recorded in THIRD-PARTY-NOTICES.md at the repository root)

The binaries are extracted unchanged; they are copied to the plugin data
directory at first use and executed from there. No modifications are made.

Source code for these binaries
------------------------------
  https://github.com/xiph/flac/releases/tag/1.4.3
  (tarball: https://downloads.xiph.org/releases/flac/flac-1.4.3.tar.xz)

  If you redistribute this plugin, you must pass on the GPL terms above for
  flac.exe, including the complete corresponding source offer above.

Which license applies to what
-----------------------------
  This plugin's own code        MIT (see LICENSE at the repository root)
  flac.exe                      GPL-2.0-or-later (COPYING.GPL)
  libFLAC.dll, libFLAC++.dll    BSD-3-Clause (FLAC-COPYING.txt)
  FLAC manuals                  GNU FDL (COPYING.FDL)

  The plugin calls flac.exe as a separate process; it does not link against
  the GPL code. See THIRD-PARTY-NOTICES.md for the full statement.

This package only ships the 64-bit (Win64) build. The 32-bit Win32 build and
metaflac.exe from the upstream archive are intentionally not included.
