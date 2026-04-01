# Changelog

All notable changes to LibTermux Android will be documented here.

## [1.0.0] - 2026-04-01

### Added
- `LibTermux` singleton entry-point with Kotlin DSL config (`termuxConfig {}`)
- `TermuxConfig` with Java Builder & Kotlin DSL support
- `VirtualFileSystem` — isolated Linux directory structure in app private storage
- `BootstrapInstaller` — downloads & installs Termux bootstrap with progress Flow
- `CommandExecutor` — run shell commands with coroutine-based API
- `CommandExecutor.executeStreaming()` — real-time output via Flow<OutputLine>
- `CommandExecutor.executePython()` / `executeNode()` — language-specific runners
- `SessionManager` — manage multiple named terminal sessions
- `TermuxBridge` — high-level developer API (run, python, node, bash, ruby, etc.)
- `PackageManager` — pkg install/uninstall/search/list wrapper
- `TermuxBackgroundService` — foreground service for background execution
- `TerminalView` — drop-in terminal UI widget
- JNI layer: PTY support, process forking, symlink helpers
- Full unit test suite
- GitHub Actions CI/CD (lint + test + build + release + publish)
- Sample application demonstrating all features
