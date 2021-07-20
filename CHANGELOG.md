# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.8.2]
### Fixed
- Handle missing content-type header in response - thanks @oliyh (#33)

### Added
- Expose executor configuration - thanks @furiel (#31)

## [0.8.1]
### Fixed
- Automatic body decompressions should work with mixed casing content-encoding - thanks @vincentjames501 (#28)

## [0.8.0]
### Added
- Option to pass in custom middleware via request map so built in convenience wrappers can use it - thanks @csgero (#24)

### Fixed
- transit does not decode in JDK11 because `.available` is always 0 (#25)

## [0.7.2]
### Fixed
- Remove reflective calls (#22) - thanks @jimpil

## [0.7.1]
### Fixed 
- Make `request` more permissive to handle request with async? but without callbacks throwing ClassCastException (#19)

## [0.7.0]
### Changed
- Make ssl-context more permissive (#17)

### Fixed
- Exceptions are not thrown correctly in async requests with default callbacks (#13)

## [0.6.0]
### Added
- More flexibility in multipart file types (#13 thanks @vincentjames501)
- Alpha support for converting response body based on content-type with `:as :auto`

### Changed
- Use cheshire `parse-stream-strict` to decode InputStream directly. (Requires cheshire 5.9.0 or later)
- Always parse JSON non-lazily to prevent stream being closed prematurely. 
See [clj-http#489](https://github.com/dakrone/clj-http/issues/489) for similar discussion.
- Simplify code by always using InputStream BodyHandler in `client.clj`, so coercion handling is pure middleware.

### Fixed
- Content type params not being parsed

## [0.5.0]
### Added
- Websocket support (thanks @vincentjames501)

## [0.4.1] - 2011-11-19
### Fixed
- Remove reflection warnings

## [0.4.0] - 2019-08-06
### Added
- Multipart support (#1)

### Changed
- Readme notes it is now a stable API and ready for use.

## [0.3.1] - 2019-07-01
### Added
- This CHANGELOG file

### Fixed
- Double encoding of query string (#3). 

## [0.3.0] - 2019-07-01
### Added
- Support custom middleware (#2). This makes it easier for 
users to make their own request function from some stack of middleware.

## 0.2.0 - 2019-06-24
### Added
- Initial release

[Unreleased]: https://github.com/gnarroway/hato/compare/v0.8.2...HEAD
[0.8.2]: https://github.com/gnarroway/hato/compare/v0.8.1...0.8.2
[0.8.1]: https://github.com/gnarroway/hato/compare/v0.8.0...0.8.1
[0.8.0]: https://github.com/gnarroway/hato/compare/v0.7.2...0.8.0
[0.7.2]: https://github.com/gnarroway/hato/compare/v0.7.1...0.7.2
[0.7.1]: https://github.com/gnarroway/hato/compare/v0.7.0...0.7.1
[0.7.0]: https://github.com/gnarroway/hato/compare/v0.6.0...0.7.0
[0.6.0]: https://github.com/gnarroway/hato/compare/v0.5.0...0.6.0
[0.5.0]: https://github.com/gnarroway/hato/compare/v0.4.1...0.5.0
[0.4.1]: https://github.com/gnarroway/hato/compare/v0.4.0...0.4.1
[0.4.0]: https://github.com/gnarroway/hato/compare/v0.3.1...0.4.0
[0.3.1]: https://github.com/gnarroway/hato/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/gnarroway/hato/compare/v0.2.0...v0.3.0
