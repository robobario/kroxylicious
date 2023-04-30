window.BENCHMARK_DATA = {
  "lastUpdate": 1682896602448,
  "repoUrl": "https://github.com/robobario/kproxy",
  "entries": {
    "kafka producer perf test Benchmark": [
      {
        "commit": {
          "author": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "committer": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "id": "633ffcf539e9d2384099b475974b95c6484c1062",
          "message": "Arbitrary change",
          "timestamp": "2023-03-09T08:43:33Z",
          "url": "https://github.com/robobario/kproxy/commit/633ffcf539e9d2384099b475974b95c6484c1062"
        },
        "date": 1681189298903,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "AVG Latency",
            "value": 8.17,
            "unit": "ms"
          },
          {
            "name": "95th Latency",
            "value": 4,
            "unit": "ms"
          },
          {
            "name": "99th Latency",
            "value": 302,
            "unit": "ms"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "committer": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "id": "ebb6839ef7013282f6d679830683c2eda85b09a4",
          "message": "Stop kroxy running to test change",
          "timestamp": "2023-04-11T21:29:16Z",
          "url": "https://github.com/robobario/kproxy/commit/ebb6839ef7013282f6d679830683c2eda85b09a4"
        },
        "date": 1681249038410,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "AVG Latency",
            "value": 6.57,
            "unit": "ms"
          },
          {
            "name": "95th Latency",
            "value": 4,
            "unit": "ms"
          },
          {
            "name": "99th Latency",
            "value": 171,
            "unit": "ms"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "committer": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "id": "f0b8b0ac35894d317d87bc86d2dac481509cd183",
          "message": "Wait for services in performance action\n\nWhy:\nWe saw a failure where kroxylicious was never started due to trying to\nreference a non-existent JAR. Because this was backgrounded it didn't\ncause the action to fail and the kafka producer performance script will\nretry forever, so the action eventually timed out after 6 hours. We want\nto fail faster to conserve action time and expose the issue.",
          "timestamp": "2023-04-11T21:26:19Z",
          "url": "https://github.com/robobario/kproxy/commit/f0b8b0ac35894d317d87bc86d2dac481509cd183"
        },
        "date": 1681266326592,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "AVG Latency",
            "value": 19.82,
            "unit": "ms"
          },
          {
            "name": "95th Latency",
            "value": 94,
            "unit": "ms"
          },
          {
            "name": "99th Latency",
            "value": 461,
            "unit": "ms"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "committer": {
            "name": "Robert Young",
            "username": "robobario",
            "email": "robeyoun@redhat.com"
          },
          "id": "633ffcf539e9d2384099b475974b95c6484c1062",
          "message": "Arbitrary change",
          "timestamp": "2023-03-09T08:43:33Z",
          "url": "https://github.com/robobario/kproxy/commit/633ffcf539e9d2384099b475974b95c6484c1062"
        },
        "date": 1682896601412,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "AVG Latency",
            "value": 166.37,
            "unit": "ms"
          },
          {
            "name": "95th Latency",
            "value": 631,
            "unit": "ms"
          },
          {
            "name": "99th Latency",
            "value": 1000,
            "unit": "ms"
          }
        ]
      }
    ]
  }
}