# Third-party notices

Dream Player's codec-enabled APK includes `nextlib-media3ext`, an FFmpeg-based
Media3 decoder extension distributed under GNU GPL v3.0. Its project and source
are available at <https://github.com/anilbeesetti/nextlib>.

AndroidX Media3 is distributed under Apache License 2.0. FFmpeg components have
their own upstream licensing terms. Review these licenses before redistributing
an APK; the repository's MIT license does not override third-party terms.
Dream Player's MVC extractor is adapted from AndroidX Media3 1.10.1's
`MatroskaExtractor`, retaining its Apache License 2.0 copyright notice.

SMB share enumeration uses jCIFS-ng, distributed under the GNU Lesser General
Public License (LGPL). SMB file streaming uses SMBJ, distributed under Apache
License 2.0.

The isolated MakeMKV MVC proof of concept builds Edge264, distributed under the
BSD 3-Clause License. Edge264 is not yet linked into Dream Player's live
playback path. Its copyright and license are reproduced in
`third_party/edge264/LICENSE_BSD.txt`.
