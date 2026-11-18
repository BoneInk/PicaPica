# Third-party protocol reference

The PicACG request signing, headers, endpoint names, and response field mapping in
`PicaProtocol.kt` and `PicaClient.kt` were adapted from niuhuan/pica-go, under the MIT license.

- Source: https://github.com/niuhuan/pica-go
- Reference revision: `2c208674152c3031862a6dd0b8b99bb45835f950`
- Copyright: 2021-2021 niuhuan
- License text: [third_party/pica-go-LICENSE.txt](third_party/pica-go-LICENSE.txt)

No Pikapika, PicaComic, or Venera application code or artwork was copied. The
Android interface, storage, download engine, and fixture artwork are implemented
for PicaPica. Gradle dependencies retain their respective upstream licenses.

The known connection-route address table in `NetworkStack.kt` follows
[niuhuan/pica-rust/src/client.rs](https://github.com/niuhuan/pica-rust/blob/master/src/client.rs).
Copyright (c) 2021-2024 niuhuan, MIT. See `third_party/pica-rust-LICENSE.txt`.
Both notices are included in the APK assets and accessible from Settings.

The primary API connection address `104.19.53.76` was observed in the original
client's public initialization response (`http://68.183.234.72/init`, checked
2026-10-27). It is used only as a DNS override: credentials are sent exclusively
over verified HTTPS to `picaapi.picacomic.com`. No plaintext initialization request
or third-party proxy service is used by PicaPica itself.


The cover redirect hostname `img.picacomic.com` was observed in a real image
request on 2026-10-27. It uses the same hostname-preserving DNS route as the
existing storage hosts. Redirect behavior is also documented by
[Scighost/Pica3](https://github.com/Scighost/Pica3/blob/main/README.md).

UI color values use the Crimson and Slate palettes from
[Radix UI Colors](https://github.com/radix-ui/colors). No third-party app UI code
or artwork was incorporated.

Radix Colors license: [third_party/radix-colors-LICENSE.txt](third_party/radix-colors-LICENSE.txt), also included in the in-app license notice.

The official discussion board identifier and comments endpoint were checked against
[Scighost/Pica3 PicaClient.cs](https://github.com/Scighost/Pica3/blob/60b24e8e3d8845fe7d4512c0f64838858f5dba1c/Pica3.CoreApi/PicaClient.cs)
and verified through a read-only request on 2026-10-27. Only endpoint facts were
referenced; no implementation code was copied.

Registration field names and the profile/avatar PUT endpoints were also checked
against niuhuan/pica-rust `src/entities/auth.rs`, `src/client.rs` and the pica-go
reference above on 2026-10-28. Only protocol facts were referenced.

The PicACG punch-in endpoint and profile `isPunched` field were checked against
[niuhuan/pica-go client.go](https://github.com/niuhuan/pica-go/blob/master/client.go)
and `dto.go` on 2026-11-02. Only protocol facts were referenced. Xiangxiang home
sections, manual check-in, registration email verification and form fields were
checked against the site's public pages and scripts on the same date; no website
scripts or advertising code are executed or included in the APK.


The Xiangxiang source uses protocol field names, HTML selectors and image slice
coordinates observed from the public website at `https://fulovehouse.net/` on
2026-11-02. The address was checked against its announcement board at
`https://trello.com/b/V9TEaHIa`. Requests and image reconstruction are independently
implemented; website scripts, advertising, artwork and page assets are not bundled.

HTML parsing uses [jsoup 1.18.3](https://jsoup.org/), under the MIT license. See
[third_party/jsoup-LICENSE.txt](third_party/jsoup-LICENSE.txt), also included in the
in-app license notice.
