# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- Simplify code by always using InputStream BodyHandler in client.clj, so coercion handling is pure middleware.
This is to open the door to pluggable body handlers middleware.

## [0.5.0]
### Added
- Websocket support (thanks @vincentjames501)

## [0.4.1] - 2011-11-19
### Fixed
- Remove reflection warnings

## [0.4.0] - 2019-08-06
### Added
- Multipart support ([#1](https://github.com/gnarroway/hato/issues/1))

### Changed
- Readme notes it is now a stable API and ready for use.

## [0.3.1] - 2019-07-01
### Added
- This CHANGELOG file

### Fixed
- Double encoding of query string ([#3](https://github.com/gnarroway/hato/issues/3)). 

## [0.3.0] - 2019-07-01
### Added
- Support custom middleware ([#2](https://github.com/gnarroway/hato/issues/2)). This makes it easier for 
users to make their own request function from some stack of middleware.

## 0.2.0 - 2019-06-24
### Added
- Initial release

[Unreleased]: https://github.com/gnarroway/hato/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/gnarroway/hato/compare/v0.4.1...0.5.0
[0.4.1]: https://github.com/gnarroway/hato/compare/v0.4.0...0.4.1
[0.4.0]: https://github.com/gnarroway/hato/compare/v0.3.1...0.4.0
[0.3.1]: https://github.com/gnarroway/hato/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/gnarroway/hato/compare/v0.2.0...v0.3.0
