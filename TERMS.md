# IdleStow Terms of Use

_Version: 2026-11-25 · Applies to IdleStow 0.1.1 and later until replaced_

> **Draft. This document has not been reviewed by a lawyer.** It is written in plain language to explain the
> conditions that already come with the free software license, and the risks of using a tool that changes how
> files are stored. Nothing here limits any right you have under a law that cannot be waived or limited by
> agreement.

These terms are between you and Mansoor Alakoozai, the author and maintainer of IdleStow ("I", "me"). IdleStow
is a personal, non-commercial open-source project. It is not sold, has no paid features and is not supplied by a
company.

## 1. The license comes first

IdleStow is licensed under the [Apache License, Version 2.0](LICENSE). The license gives you the right to use,
copy, change and share IdleStow. Its warranty disclaimer (section 7) and limitation of liability (section 8)
apply to everyone who uses the software. These Terms of Use do not take away any permission the license gives
you. If these terms and the license ever conflict, the license wins.

## 2. What IdleStow does

IdleStow looks at folders **you** choose, finds files you have not used for a while, and can compress them to
save disk space. Depending on your choices it uses Windows (NTFS) transparent compression, or it creates ZIP or
tar.zst archives. Every change is verified, recorded and designed to be undoable. See the
[user guide](docs/user-guide/README.md) and the [safety model](docs/architecture/safety-model.md) for how this
works and what its limits are.

## 2a. Updates

If you allow it, IdleStow checks GitHub for newer versions, and it can download and install an update when you
choose to. An update replaces IdleStow's own program folder (never your files or IdleStow's data folder) after
checking the download against the checksums published with the release. The previous version is kept next to it
as `IdleStow.previous`. Updates are provided on the same terms as the rest of IdleStow, with no promise that
updates will be made or will work. You can turn automatic checks off at any time.

## 3. Your responsibilities

By using IdleStow you agree that:

1. **You keep your own backups.** Compression is not a backup. Before using IdleStow on files that matter to
   you, make sure you have an up-to-date backup stored somewhere else.
2. **You only use it on files and computers you are allowed to manage.** Do not point IdleStow at folders
   you do not own or have no permission to change, for example a work computer against your employer's rules,
   or another person's files.
3. **You review what it proposes.** IdleStow shows what it plans to do before it changes anything (unless you
   turn on fully autonomous mode yourself, which asks you to confirm exactly what it may do). Its estimates of
   space savings and its "idle file" decisions are educated guesses, not promises.
4. **You do not use it where a failure could cause serious harm.** IdleStow is not designed for safety-critical,
   medical, legal-hold, regulated-records or other systems where losing or delaying access to a file could put
   people, property or legal obligations at risk.
5. **You keep restore information.** Do not delete IdleStow's data folder, retained originals
   (`.idlestow/retained`) or recovery manifests while files still need restoring.

## 4. No warranty

IdleStow is provided **"as is" and "as available"**, without warranties or conditions of any kind, express or
implied, to the fullest extent permitted by law. This includes, without limitation, any warranty or condition of
merchantability, quality, fitness for a particular purpose, non-infringement, or that the software will be
error-free, uninterrupted, will save any particular amount of space, or will never lose or damage data.

Some places do not allow some warranties or conditions to be excluded. If that applies to you, the excluded
items above do not apply to you to the extent the law forbids it.

## 5. Limitation of liability

To the fullest extent permitted by law, I am not liable for any direct, indirect, incidental, special,
consequential or exemplary damages, or for any loss of data, files, profits, revenue, business, goodwill or
use, arising out of or related to IdleStow or these terms, however caused and under any theory of liability
(contract, tort including negligence, or otherwise), even if I was told such damage was possible.

This limitation does not apply where the law does not allow it, for example (where applicable) to liability for
death or personal injury caused by negligence, for fraud, or for deliberate or grossly negligent acts. Nothing
in these terms limits rights you have as a consumer that cannot be limited by contract.

## 6. Support and changes

IdleStow is a volunteer project. There is no promise of support, updates, bug fixes or that the project will
continue. You can report problems on [GitHub](https://github.com/MaDaAi/IdleStow/issues) (do not post private
file names or data) and security issues privately as described in [SECURITY.md](SECURITY.md).

I may update these terms. The version date at the top changes when I do, and IdleStow asks you to agree again
the next time it starts after a material change. The terms that applied when you used a given version continue
to apply to that use.

## 7. Third-party software

IdleStow bundles third-party components (including a Java runtime, JavaFX, CPython and several libraries),
each under its own license. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Those licenses govern those
components.

## 8. Trademarks

"IdleStow" and its logo identify this project. The Apache License does not grant trademark rights (section 6
of the license). You may use the name to refer to the project truthfully, but please do not use it in a way
that suggests your modified version or product is the official IdleStow or is endorsed by me. No trademark
registration is claimed.

## 9. Privacy

IdleStow does not collect or send any personal information. See the [Privacy notice](PRIVACY.md).

## 10. Governing law

These terms are governed by the laws of the Province of Ontario and the federal laws of Canada that apply
there, without regard to conflict-of-law rules. This choice does not deprive you of the protection of mandatory
laws of the place where you live, and it does not stop you from bringing a claim in a court where the law gives
you the right to do so.

## 11. If part of these terms is invalid

If a court finds part of these terms unenforceable, that part is limited to the minimum extent necessary and
the rest stays in effect.

## 12. Contact

Open an issue at https://github.com/MaDaAi/IdleStow, or use the repository's private security reporting for
vulnerabilities.
