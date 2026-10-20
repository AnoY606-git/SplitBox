**English** · [Русский](../../SECURITY.md) · [Developer guide](README.md)

# Security

SplitBox processes APKs/archives selected by the user and uses Root/Shizuku only when explicitly selected and granted access. It has no network permission; files and history are not sent to a server.

Areas requiring particular care include ZIP traversal/limits, shell injection, OBB destinations, signatures/split sets, session correlation, and false success results. Use project-owned test APKs and a separate device/emulator for reproduction.

If the published GitHub repository offers **Security → Report a vulnerability**, use that private channel. The repository owner should enable Private vulnerability reporting when publishing. Until a private channel is configured, do not publish an exploit, private APKs, or sensitive logs in an ordinary issue; first agree on a safe transfer method with the maintainer.

Provide a minimal description, affected version, Android/ROM, backend, and safe reproduction steps. Reports do not require signing keys, passwords, or personal data. Ordinary UI/compatibility bugs can use the bug report template.

Coverage and untested devices are described in [Verification](VERIFICATION.md). The source undergoes targeted checks and tests; a full independent security audit is not claimed.
