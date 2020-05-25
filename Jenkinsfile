buildPlugin(configurations: [
  // Test the long-term support end of the compatibility spectrum (i.e., the minimum required
  // Jenkins version).
  [ platform: 'linux', jdk: '8', jenkins: null ],

  // Test the common case (i.e., a recent LTS release) on both Linux and Windows.
  [ platform: 'linux', jdk: '8', jenkins: '2.222.3', javaLevel: '8' ],
  [ platform: 'windows', jdk: '8', jenkins: '2.222.3', javaLevel: '8' ],

  // Test with the first version of Jenkins that supports the WebSocket protocol on both Linux and
  // Windows. This can be removed once WebSocket support is shipped in an LTS release.
  [ platform: 'linux', jdk: '8', jenkins: '2.229', javaLevel: '8' ],
  [ platform: 'windows', jdk: '8', jenkins: '2.229', javaLevel: '8' ],

  // Test the bleeding edge of the compatibility spectrum (i.e., the latest supported Java runtime).
  [ platform: 'linux', jdk: '11', jenkins: '2.222.3', javaLevel: '8' ],
])
