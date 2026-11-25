package com.gabriel.motolink;

/**
 * Certificado de "central de referência" do Android Auto (o mesmo dos simuladores de central de
 * código aberto). O celular só conversa com a central depois de um TLS em que ela apresenta isto.
 */
final class CertificadoAA {
    private CertificadoAA() {}

    static final String CERT =
            "MIIDJTCCAg0CAnZTMA0GCSqGSIb3DQEBCwUAMFsxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1N" +
            "b3VudGFpbiBWaWV3MR8wHQYDVQQKDBZHb29nbGUgQXV0b21vdGl2ZSBMaW5rMB4XDTE0MDcwODIyNDkxOFoXDTQ0MDcwNzIyNDkx" +
            "OFowVTELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAkNBMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MSEwHwYDVQQKDBhHb29nbGUtQW5k" +
            "cm9pZC1SZWZlcmVuY2UwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCpqQmvoDW/XsREoj20dRcMqJGWh8RlUoHB8CpB" +
            "psoqV4nAuvNngkyrdpCf1yg0fVAp2Ugj5eOtzbiN6BxoNHpPgiZ64pc+JRlwjmyHpssDaHzP+zHZM7acwMcroNVyynSzpiydEDyx" +
            "/KPtEz5AsKi7c7AYYEtnCmAnK/waN1RT5KdZ9f97D9NeF7Ljdk+IKFROJh7Nv/YGiv9GdPZh/ezSm2qhD3gzdh9PYs2cu0u+N17P" +
            "YpSYB7vXPcYa/gmIVipIJ5RuMQVBWrCgtfzwKPqbnJQVykm8LnysK+8RCgmPLN3uhsZx6Whax2TVXb1q68DoiaFPhvMfPr2i/9IK" +
            "aC69AgMBAAEwDQYJKoZIhvcNAQELBQADggEBAIpfjQriEtbpUyWLoOOfJsjFN04+ajq91XALCPd+2ixWHZIBJiucrrf0H7OgY7eF" +
            "nNbU0cRqiDZHI8BtvzFxNi/JgXqCmSHRrlaoIsITfqo8KHwcAMs4qWTeLQmkTXBZYz0M3HwC7N1vOGjAJJN5qENIm1Jq+/3cfxVg" +
            "2zhHPKY8qtdgl73YIXb9Xx3WmPCBeRBCKJncj0Rq14uaOjWXRyBgbmdzMXJzFGPHx3wN04JqGyfPFlDazXExFQwuAryjoYBRdxPx" +
            "GufeQCp3am4xxI2oxNIzR+4LnOcDhgU1B7sbkVzbKj5gjdOQAmxnKCfBtUNB63a7yzGPYGPIwlBsm54=";

    static final String CHAVE =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCpqQmvoDW/XsREoj20dRcMqJGWh8RlUoHB8CpBpsoqV4nAuvNn" +
            "gkyrdpCf1yg0fVAp2Ugj5eOtzbiN6BxoNHpPgiZ64pc+JRlwjmyHpssDaHzP+zHZM7acwMcroNVyynSzpiydEDyx/KPtEz5AsKi7" +
            "c7AYYEtnCmAnK/waN1RT5KdZ9f97D9NeF7Ljdk+IKFROJh7Nv/YGiv9GdPZh/ezSm2qhD3gzdh9PYs2cu0u+N17PYpSYB7vXPcYa" +
            "/gmIVipIJ5RuMQVBWrCgtfzwKPqbnJQVykm8LnysK+8RCgmPLN3uhsZx6Whax2TVXb1q68DoiaFPhvMfPr2i/9IKaC69AgMBAAEC" +
            "ggEAbBoW3963IG6jpA+0PW11+EzYJw/u5ZiCsS3z3s0Fd6E7VqBIQyXU8FOlpxMSvQ8zqtaVjroGLlIsS88feo4leM+28Qm70I8W" +
            "/I7jPDPcmxlSnbqycnDu5EY5IeVi27eAUI+LUbBs3APb900Rl2p4uKfoBkAlC0yjI5J1GcczZhf7RDh1wGgFWZI+ljiSrfpdiA4X" +
            "mcZ9c7FlO5+NTotZzYeNx1iZprajV1/dlDy8UWEkwoWtppeGzUf3HHgl8yay62ub2vo5I1Z7Z98Roq8KC1o7k2IXOrHztCl3X03g" +
            "MwlIF4WQ6Fx5LZDU9dfaPhzkutekVgbtO9SzHgb3NXCZwQKBgQDcSS/OLll18ssjBwc7PsdaIFIPlF428Tk8qezEnDmHS6xeztkG" +
            "npOlilk9jYSsVUbQmq8MwBSjfMVH95B0w0yyfOYqjgTocg4lRCoPuBdnuBY/lU1Lws4FoGsGMNFkHWjHzl622mavkJiDzWA+CORP" +
            "UllS/DnPKJnZk2n0zZRKaQKBgQDFKqvePMx/a/ayQ09UZYxov0vwRyNkHevmwEGQjOiHKozWvLqWhCvFtwo+VqHqmCw95cYUpg1G" +
            "vppB6Lnw2uHgWAWxr3ugDjaRYSqG/L7FG6FDF+1sPvBuxNpBmto59TI1fBFmU9VBGLDnr1M27qH3KTWlA3lCsovV6Dbk7D+vNQKB" +
            "gE6GgFYdS6KyFBu+a6OA84t7LgWDvDoVr3Oil1ZW4mMKZL2/OroTWUqPkNRSWFMeawn9uhzvc+v7lE/dPk+BNxwBTgMpcTJzRfue" +
            "2ueTljRQ+Q1daZpyLQLwdnZUfLAVk752IGlKXYSEJPoHAiHbBZgJIPJmGy1vqbhXxlOP3SbRAoGBAJoAQ2/5gy0/sdf5FRxxmOM0" +
            "D+dkWTNY36pDnrJ+LR1uUcVkckUghWQQHRMl7aBkLaJHN5lnPdV1CN3UHnAPNwBZIFFyJJiWoW6aO3JmNceVVjcmmE7FNlz+qw81" +
            "GaDNcOMvvhN0BYyr8Xl1iwTMDXwVFw6FkRBUjz6L+1yBXxjFAoGAJZcU+tEM1+gHPCqHK2bPkfYOCyEAro4zY/VWXZKHgCoPau8U" +
            "c9+vFu2QVMb5kVyLTdyRLQKpooR6f8En6utS/G15YuqRYqzSTrMBzpRrqIwbgKI9RHNPAvhtVAmXnwsYDPIQ1rrELK6WzTjUySRd" +
            "7gyCoq+DlY7ZKDa7FUz05Ek=";
}
