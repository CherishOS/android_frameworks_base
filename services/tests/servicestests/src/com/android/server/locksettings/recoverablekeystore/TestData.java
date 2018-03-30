package com.android.server.locksettings.recoverablekeystore;

import static com.google.common.truth.Truth.assertThat;

import com.android.server.locksettings.recoverablekeystore.certificate.CertUtils;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPath;
import java.security.spec.ECPrivateKeySpec;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class TestData {

    private static final String KEY_ALGORITHM = "AES";
    private static final long DEFAULT_SERIAL = 1000;
    private static final String CERT_PATH_ENCODING = "PkiPath";

    private static final String CERT_PATH_1_BASE64 = ""
            + "MIIIPzCCBS8wggMXoAMCAQICAhAAMA0GCSqGSIb3DQEBCwUAMCAxHjAcBgNVBAMM"
            + "FUdvb2dsZSBDcnlwdEF1dGhWYXVsdDAeFw0xODAyMDMwMDQyMDNaFw0yODAyMDEw"
            + "MDQyMDNaMC0xKzApBgNVBAMMIkdvb2dsZSBDcnlwdEF1dGhWYXVsdCBJbnRlcm1l"
            + "ZGlhdGUwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDckHib0X6rQyDq"
            + "k4519b5du0OrCPk30XXKwz+Hz5y4cGZaWKGcHOHWS2X9YApRzO00/EbvFkWVUTVG"
            + "27wJ54V+C3HHSOAUWHhEgfFWvvHwfn9HTDx1BEk79aQqJ7DuJ06Sn/WOiMtKVAT5"
            + "6Mi8mekBxpMOrdZqwlcLrUVsZxEHsw5/ceZu4cSWzc7SzlnbNK1cCgyRDGqWf6Gp"
            + "3hGE86kUOtM1i95RgUIpw+w/z0wxpF6kIyQTjK+KjiYH/RBOJIEcm6sSWZlMotKL"
            + "Sn2lhf+XL8yUxExIHTosfeb077QWW4w2BB2NZM4wPAO3w4aw33FNigDQc2SQYmnU"
            + "EYmIcD8kx77+JWCgCxBJc2zTHXtBxWuXAQ+iegt8RO+QD97pd6XKM9xPsAOkcWLp"
            + "79o+AJol4P5fwvgYM69mM4lwH12v86RI4aptPQOag0KDIHXyKbjaQyAgv30l4KkD"
            + "pf2uWODhOOTwNbVPYUm3sYUlhBcbyhTk8YqN9sPU4QAao5sKTAYZgB/mlheQypTU"
            + "wyvqz6bRzGehVB3ltP9gCyKdI04VXEUuUBWk3STyV2REQen5/LKAns6v11Cz22Zr"
            + "EdCvNLgetnyV7CJsOa/wD/GiUWL2Ta7pzshi9ahJqrrcNPRbAzOLcNKZkFexhzPp"
            + "onuo/pNrcaRda1frepXxVkmbsgOULwIDAQABo2YwZDAdBgNVHQ4EFgQUd6md2hCP"
            + "lmf3VkEX5FfDxKBLbaAwHwYDVR0jBBgwFoAUm2X66jmB+eBCaZHSjGYzHM/x6fgw"
            + "EgYDVR0TAQH/BAgwBgEB/wIBATAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQEL"
            + "BQADggIBAFgShhuW+WVTowN080PLf0TWPlHACHHUPghf7rFGxgUjJypCloE84Beg"
            + "3ROpP5l19CDqZ9OyPzA1z6VAzeGXyFhZvby7G2tZDBRP/v0u8pnSAdC5F8l8Vh2Y"
            + "GdgE3sZD25vpdBi7P0Ef6LYQetOJXn86PqgmgW1F6lzxDjKCsi9kpeU0AWwDdOVg"
            + "748wku50o8UEzsVwxzFd9toGlge/nn3FH5J7EuGzAlFwToHqpwTVEegaAd0l9mr5"
            + "+rS7Urd3X80BHDqCBcXE7Uqbtzw5Y+lmowMCnW0kFN02dC9dLt2c9IxC+9sPIA5e"
            + "TkrZBkrkTVRGLj2r29j7nC9m5VaKcBqcLZDWy8pRna8yaZprgNdE8d/WTY9nVsic"
            + "09N8zNF5Q0bhhWa3QonlB9XW5ZqDguiclvn+5TtREzSAtSOyxM+gfG3l0wjOywIk"
            + "1aFa52RaqAWPL67KOM6G3vKNpMnW5hrmHrijuKxiarGIoZfkZMR5ijK0uFgv3/p6"
            + "NHL/YQBaHJJhkKet5ThiPxwW9+1k/ZcXVeY26Xh+22Gp/8to7ZW8guPPiN1hfpD+"
            + "7f1IdSmHDrsZQQ7bfzV0bppsyNNB7e2Ecyw+GQny27nytBLJDGdRBurbwQvzppQO"
            + "6Qmlk0rfCszh7bGCoCQNxXmuDsQ5BC+pQUqJplTqds1smyi29xs3MIIDCDCB8aAD"
            + "AgECAgYBYVkuU0cwDQYJKoZIhvcNAQELBQAwLTErMCkGA1UEAwwiR29vZ2xlIENy"
            + "eXB0QXV0aFZhdWx0IEludGVybWVkaWF0ZTAeFw0xODAyMDIwMTAxMDNaFw0yMDAy"
            + "MDMwMTAxMDNaMCkxJzAlBgNVBAMTHkdvb2dsZSBDcnlwdEF1dGhWYXVsdCBJbnN0"
            + "YW5jZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLgAERiYHfButJT+htocB40B"
            + "tDr2jdxh0EZJlQ8QhpMkZuA/0t/zeSAdkVWw5b16izJ9JVOi/KVl4b0hRH54Uvow"
            + "DQYJKoZIhvcNAQELBQADggIBAJ3PM4GNTNYzMr8E/IGsWZkLx9ARAALqBXz7As59"
            + "F8y5UcLMqkXD/ewOfBZgF5VzjlAePyE/wSw0wc3xzvrDVVDiZaMBW1DVtSlbn25q"
            + "00m00mmcUeyyMc7vuRkPoDshIMQTc8+U3yyYsVScSV+B4TvSx6wPZ9FpwnSPjVPD"
            + "2GkqeMTWszuxNVEWq0wmm0K5lMaX0hfiak+4/IZxOPPGIg2py1KLA/H2gdyeqyJR"
            + "cAsyEkfwLlushR5T9abSiPsIRcYoX8Ck8Lt+gQ7RCMefnm8CoOBKIfcjuV4PGOoe"
            + "Xrq57VR5SsOeT07bL+D7B+mohYFI1v2G3WClAE8XgM3q8NoFFvaYmoi0+UcTduil"
            + "47qvozjdNmjRAgu5j6vMKXEdG5Rqsja8hy0LG1hwfnR0gNiwcZ5Le3GyFnwH1Igq"
            + "vsGOUM0ohnDUAU0zJY7nG0QYrDYe5/QPRNhWDpYkwHDiqcG28wIQCOTPAZHU2EoS"
            + "KjSqEG2l0S5JPcor2BEde9ikSkcmK8foxlOHIdFn+n7RNF3bSEfKn1IOuXoqPidm"
            + "eBQLevqG8KTy/C9CHqlaCNlpbIA9h+WVfsjm2s6JXBu0YbcfoIbJAmSuZVeqB/+Z"
            + "Vvpfiad/jQWzY49fRnsSmV7VveTFPGtJxC89EadbMAinMZo+72u59319RqN5wsP2"
            + "Zus8";
    private static String CERT_PATH_2_BASE64 = ""
            + "MIIFMzCCBS8wggMXoAMCAQICAhAAMA0GCSqGSIb3DQEBCwUAMCAxHjAcBgNVBAMM"
            + "FUdvb2dsZSBDcnlwdEF1dGhWYXVsdDAeFw0xODAyMDMwMDQyMDNaFw0yODAyMDEw"
            + "MDQyMDNaMC0xKzApBgNVBAMMIkdvb2dsZSBDcnlwdEF1dGhWYXVsdCBJbnRlcm1l"
            + "ZGlhdGUwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQDckHib0X6rQyDq"
            + "k4519b5du0OrCPk30XXKwz+Hz5y4cGZaWKGcHOHWS2X9YApRzO00/EbvFkWVUTVG"
            + "27wJ54V+C3HHSOAUWHhEgfFWvvHwfn9HTDx1BEk79aQqJ7DuJ06Sn/WOiMtKVAT5"
            + "6Mi8mekBxpMOrdZqwlcLrUVsZxEHsw5/ceZu4cSWzc7SzlnbNK1cCgyRDGqWf6Gp"
            + "3hGE86kUOtM1i95RgUIpw+w/z0wxpF6kIyQTjK+KjiYH/RBOJIEcm6sSWZlMotKL"
            + "Sn2lhf+XL8yUxExIHTosfeb077QWW4w2BB2NZM4wPAO3w4aw33FNigDQc2SQYmnU"
            + "EYmIcD8kx77+JWCgCxBJc2zTHXtBxWuXAQ+iegt8RO+QD97pd6XKM9xPsAOkcWLp"
            + "79o+AJol4P5fwvgYM69mM4lwH12v86RI4aptPQOag0KDIHXyKbjaQyAgv30l4KkD"
            + "pf2uWODhOOTwNbVPYUm3sYUlhBcbyhTk8YqN9sPU4QAao5sKTAYZgB/mlheQypTU"
            + "wyvqz6bRzGehVB3ltP9gCyKdI04VXEUuUBWk3STyV2REQen5/LKAns6v11Cz22Zr"
            + "EdCvNLgetnyV7CJsOa/wD/GiUWL2Ta7pzshi9ahJqrrcNPRbAzOLcNKZkFexhzPp"
            + "onuo/pNrcaRda1frepXxVkmbsgOULwIDAQABo2YwZDAdBgNVHQ4EFgQUd6md2hCP"
            + "lmf3VkEX5FfDxKBLbaAwHwYDVR0jBBgwFoAUm2X66jmB+eBCaZHSjGYzHM/x6fgw"
            + "EgYDVR0TAQH/BAgwBgEB/wIBATAOBgNVHQ8BAf8EBAMCAYYwDQYJKoZIhvcNAQEL"
            + "BQADggIBAFgShhuW+WVTowN080PLf0TWPlHACHHUPghf7rFGxgUjJypCloE84Beg"
            + "3ROpP5l19CDqZ9OyPzA1z6VAzeGXyFhZvby7G2tZDBRP/v0u8pnSAdC5F8l8Vh2Y"
            + "GdgE3sZD25vpdBi7P0Ef6LYQetOJXn86PqgmgW1F6lzxDjKCsi9kpeU0AWwDdOVg"
            + "748wku50o8UEzsVwxzFd9toGlge/nn3FH5J7EuGzAlFwToHqpwTVEegaAd0l9mr5"
            + "+rS7Urd3X80BHDqCBcXE7Uqbtzw5Y+lmowMCnW0kFN02dC9dLt2c9IxC+9sPIA5e"
            + "TkrZBkrkTVRGLj2r29j7nC9m5VaKcBqcLZDWy8pRna8yaZprgNdE8d/WTY9nVsic"
            + "09N8zNF5Q0bhhWa3QonlB9XW5ZqDguiclvn+5TtREzSAtSOyxM+gfG3l0wjOywIk"
            + "1aFa52RaqAWPL67KOM6G3vKNpMnW5hrmHrijuKxiarGIoZfkZMR5ijK0uFgv3/p6"
            + "NHL/YQBaHJJhkKet5ThiPxwW9+1k/ZcXVeY26Xh+22Gp/8to7ZW8guPPiN1hfpD+"
            + "7f1IdSmHDrsZQQ7bfzV0bppsyNNB7e2Ecyw+GQny27nytBLJDGdRBurbwQvzppQO"
            + "6Qmlk0rfCszh7bGCoCQNxXmuDsQ5BC+pQUqJplTqds1smyi29xs3";

    private static final String THM_CERT_XML_BEFORE_SERIAL = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<certificates>\n"
                    + "  <metadata>\n"
                    + "    <serial>\n"
                    + "      ";
    private static final String THM_CERT_XML_AFTER_SERIAL = "\n"
            + "    </serial>\n"
            + "    <creation-time>\n"
            + "      1515697631\n"
            + "    </creation-time>\n"
            + "    <refresh-interval>\n"
            + "      2592000\n"
            + "    </refresh-interval>\n"
            + "    <previous>\n"
            + "      <serial>\n"
            + "        0\n"
            + "      </serial>\n"
            + "      <hash>\n"
            + "        47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\n"
            + "      </hash>\n"
            + "    </previous>\n"
            + "  </metadata>\n"
            + "  <intermediates>\n"
            + "    <cert>\n"
            + "      MIIFLzCCAxegAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVR29v\n"
            + "      Z2xlIENyeXB0QXV0aFZhdWx0MB4XDTE4MDIwMzAwNDIwM1oXDTI4MDIwMTAwNDIw\n"
            + "      M1owLTErMCkGA1UEAwwiR29vZ2xlIENyeXB0QXV0aFZhdWx0IEludGVybWVkaWF0\n"
            + "      ZTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANyQeJvRfqtDIOqTjnX1\n"
            + "      vl27Q6sI+TfRdcrDP4fPnLhwZlpYoZwc4dZLZf1gClHM7TT8Ru8WRZVRNUbbvAnn\n"
            + "      hX4LccdI4BRYeESB8Va+8fB+f0dMPHUESTv1pConsO4nTpKf9Y6Iy0pUBPnoyLyZ\n"
            + "      6QHGkw6t1mrCVwutRWxnEQezDn9x5m7hxJbNztLOWds0rVwKDJEMapZ/oaneEYTz\n"
            + "      qRQ60zWL3lGBQinD7D/PTDGkXqQjJBOMr4qOJgf9EE4kgRybqxJZmUyi0otKfaWF\n"
            + "      /5cvzJTETEgdOix95vTvtBZbjDYEHY1kzjA8A7fDhrDfcU2KANBzZJBiadQRiYhw\n"
            + "      PyTHvv4lYKALEElzbNMde0HFa5cBD6J6C3xE75AP3ul3pcoz3E+wA6RxYunv2j4A\n"
            + "      miXg/l/C+Bgzr2YziXAfXa/zpEjhqm09A5qDQoMgdfIpuNpDICC/fSXgqQOl/a5Y\n"
            + "      4OE45PA1tU9hSbexhSWEFxvKFOTxio32w9ThABqjmwpMBhmAH+aWF5DKlNTDK+rP\n"
            + "      ptHMZ6FUHeW0/2ALIp0jThVcRS5QFaTdJPJXZERB6fn8soCezq/XULPbZmsR0K80\n"
            + "      uB62fJXsImw5r/AP8aJRYvZNrunOyGL1qEmqutw09FsDM4tw0pmQV7GHM+mie6j+\n"
            + "      k2txpF1rV+t6lfFWSZuyA5QvAgMBAAGjZjBkMB0GA1UdDgQWBBR3qZ3aEI+WZ/dW\n"
            + "      QRfkV8PEoEttoDAfBgNVHSMEGDAWgBSbZfrqOYH54EJpkdKMZjMcz/Hp+DASBgNV\n"
            + "      HRMBAf8ECDAGAQH/AgEBMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOC\n"
            + "      AgEAWBKGG5b5ZVOjA3TzQ8t/RNY+UcAIcdQ+CF/usUbGBSMnKkKWgTzgF6DdE6k/\n"
            + "      mXX0IOpn07I/MDXPpUDN4ZfIWFm9vLsba1kMFE/+/S7ymdIB0LkXyXxWHZgZ2ATe\n"
            + "      xkPbm+l0GLs/QR/othB604lefzo+qCaBbUXqXPEOMoKyL2Sl5TQBbAN05WDvjzCS\n"
            + "      7nSjxQTOxXDHMV322gaWB7+efcUfknsS4bMCUXBOgeqnBNUR6BoB3SX2avn6tLtS\n"
            + "      t3dfzQEcOoIFxcTtSpu3PDlj6WajAwKdbSQU3TZ0L10u3Zz0jEL72w8gDl5OStkG\n"
            + "      SuRNVEYuPavb2PucL2blVopwGpwtkNbLylGdrzJpmmuA10Tx39ZNj2dWyJzT03zM\n"
            + "      0XlDRuGFZrdCieUH1dblmoOC6JyW+f7lO1ETNIC1I7LEz6B8beXTCM7LAiTVoVrn\n"
            + "      ZFqoBY8vrso4zobe8o2kydbmGuYeuKO4rGJqsYihl+RkxHmKMrS4WC/f+no0cv9h\n"
            + "      AFockmGQp63lOGI/HBb37WT9lxdV5jbpeH7bYan/y2jtlbyC48+I3WF+kP7t/Uh1\n"
            + "      KYcOuxlBDtt/NXRummzI00Ht7YRzLD4ZCfLbufK0EskMZ1EG6tvBC/OmlA7pCaWT\n"
            + "      St8KzOHtsYKgJA3Fea4OxDkEL6lBSommVOp2zWybKLb3Gzc=\n"
            + "    </cert>\n"
            + "  </intermediates>\n"
            + "  <endpoints>\n"
            + "    <cert>\n"
            + "      MIIDCDCB8aADAgECAgYBYVkuU0cwDQYJKoZIhvcNAQELBQAwLTErMCkGA1UEAwwi\n"
            + "      R29vZ2xlIENyeXB0QXV0aFZhdWx0IEludGVybWVkaWF0ZTAeFw0xODAyMDIwMTAx\n"
            + "      MDNaFw0yMDAyMDMwMTAxMDNaMCkxJzAlBgNVBAMTHkdvb2dsZSBDcnlwdEF1dGhW\n"
            + "      YXVsdCBJbnN0YW5jZTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLgAERiYHfBu\n"
            + "      tJT+htocB40BtDr2jdxh0EZJlQ8QhpMkZuA/0t/zeSAdkVWw5b16izJ9JVOi/KVl\n"
            + "      4b0hRH54UvowDQYJKoZIhvcNAQELBQADggIBAJ3PM4GNTNYzMr8E/IGsWZkLx9AR\n"
            + "      AALqBXz7As59F8y5UcLMqkXD/ewOfBZgF5VzjlAePyE/wSw0wc3xzvrDVVDiZaMB\n"
            + "      W1DVtSlbn25q00m00mmcUeyyMc7vuRkPoDshIMQTc8+U3yyYsVScSV+B4TvSx6wP\n"
            + "      Z9FpwnSPjVPD2GkqeMTWszuxNVEWq0wmm0K5lMaX0hfiak+4/IZxOPPGIg2py1KL\n"
            + "      A/H2gdyeqyJRcAsyEkfwLlushR5T9abSiPsIRcYoX8Ck8Lt+gQ7RCMefnm8CoOBK\n"
            + "      IfcjuV4PGOoeXrq57VR5SsOeT07bL+D7B+mohYFI1v2G3WClAE8XgM3q8NoFFvaY\n"
            + "      moi0+UcTduil47qvozjdNmjRAgu5j6vMKXEdG5Rqsja8hy0LG1hwfnR0gNiwcZ5L\n"
            + "      e3GyFnwH1IgqvsGOUM0ohnDUAU0zJY7nG0QYrDYe5/QPRNhWDpYkwHDiqcG28wIQ\n"
            + "      COTPAZHU2EoSKjSqEG2l0S5JPcor2BEde9ikSkcmK8foxlOHIdFn+n7RNF3bSEfK\n"
            + "      n1IOuXoqPidmeBQLevqG8KTy/C9CHqlaCNlpbIA9h+WVfsjm2s6JXBu0YbcfoIbJ\n"
            + "      AmSuZVeqB/+ZVvpfiad/jQWzY49fRnsSmV7VveTFPGtJxC89EadbMAinMZo+72u5\n"
            + "      9319RqN5wsP2Zus8\n"
            + "    </cert>\n"
            + "  </endpoints>\n"
            + "</certificates>\n";
    private static final String THM_SIG_XML = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<signature>\n"
            + "  <intermediates>\n"
            + "  </intermediates>\n"
            + "  <certificate>\n"
            + "    MIIFLzCCAxegAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwIDEeMBwGA1UEAwwVR29v\n"
            + "    Z2xlIENyeXB0QXV0aFZhdWx0MB4XDTE4MDIwMzAwNDIwM1oXDTI4MDIwMTAwNDIw\n"
            + "    M1owLTErMCkGA1UEAwwiR29vZ2xlIENyeXB0QXV0aFZhdWx0IEludGVybWVkaWF0\n"
            + "    ZTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANyQeJvRfqtDIOqTjnX1\n"
            + "    vl27Q6sI+TfRdcrDP4fPnLhwZlpYoZwc4dZLZf1gClHM7TT8Ru8WRZVRNUbbvAnn\n"
            + "    hX4LccdI4BRYeESB8Va+8fB+f0dMPHUESTv1pConsO4nTpKf9Y6Iy0pUBPnoyLyZ\n"
            + "    6QHGkw6t1mrCVwutRWxnEQezDn9x5m7hxJbNztLOWds0rVwKDJEMapZ/oaneEYTz\n"
            + "    qRQ60zWL3lGBQinD7D/PTDGkXqQjJBOMr4qOJgf9EE4kgRybqxJZmUyi0otKfaWF\n"
            + "    /5cvzJTETEgdOix95vTvtBZbjDYEHY1kzjA8A7fDhrDfcU2KANBzZJBiadQRiYhw\n"
            + "    PyTHvv4lYKALEElzbNMde0HFa5cBD6J6C3xE75AP3ul3pcoz3E+wA6RxYunv2j4A\n"
            + "    miXg/l/C+Bgzr2YziXAfXa/zpEjhqm09A5qDQoMgdfIpuNpDICC/fSXgqQOl/a5Y\n"
            + "    4OE45PA1tU9hSbexhSWEFxvKFOTxio32w9ThABqjmwpMBhmAH+aWF5DKlNTDK+rP\n"
            + "    ptHMZ6FUHeW0/2ALIp0jThVcRS5QFaTdJPJXZERB6fn8soCezq/XULPbZmsR0K80\n"
            + "    uB62fJXsImw5r/AP8aJRYvZNrunOyGL1qEmqutw09FsDM4tw0pmQV7GHM+mie6j+\n"
            + "    k2txpF1rV+t6lfFWSZuyA5QvAgMBAAGjZjBkMB0GA1UdDgQWBBR3qZ3aEI+WZ/dW\n"
            + "    QRfkV8PEoEttoDAfBgNVHSMEGDAWgBSbZfrqOYH54EJpkdKMZjMcz/Hp+DASBgNV\n"
            + "    HRMBAf8ECDAGAQH/AgEBMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsFAAOC\n"
            + "    AgEAWBKGG5b5ZVOjA3TzQ8t/RNY+UcAIcdQ+CF/usUbGBSMnKkKWgTzgF6DdE6k/\n"
            + "    mXX0IOpn07I/MDXPpUDN4ZfIWFm9vLsba1kMFE/+/S7ymdIB0LkXyXxWHZgZ2ATe\n"
            + "    xkPbm+l0GLs/QR/othB604lefzo+qCaBbUXqXPEOMoKyL2Sl5TQBbAN05WDvjzCS\n"
            + "    7nSjxQTOxXDHMV322gaWB7+efcUfknsS4bMCUXBOgeqnBNUR6BoB3SX2avn6tLtS\n"
            + "    t3dfzQEcOoIFxcTtSpu3PDlj6WajAwKdbSQU3TZ0L10u3Zz0jEL72w8gDl5OStkG\n"
            + "    SuRNVEYuPavb2PucL2blVopwGpwtkNbLylGdrzJpmmuA10Tx39ZNj2dWyJzT03zM\n"
            + "    0XlDRuGFZrdCieUH1dblmoOC6JyW+f7lO1ETNIC1I7LEz6B8beXTCM7LAiTVoVrn\n"
            + "    ZFqoBY8vrso4zobe8o2kydbmGuYeuKO4rGJqsYihl+RkxHmKMrS4WC/f+no0cv9h\n"
            + "    AFockmGQp63lOGI/HBb37WT9lxdV5jbpeH7bYan/y2jtlbyC48+I3WF+kP7t/Uh1\n"
            + "    KYcOuxlBDtt/NXRummzI00Ht7YRzLD4ZCfLbufK0EskMZ1EG6tvBC/OmlA7pCaWT\n"
            + "    St8KzOHtsYKgJA3Fea4OxDkEL6lBSommVOp2zWybKLb3Gzc=\n"
            + "  </certificate>\n"
            + "  <value>\n"
            + "    uKJ4W8BPCdVaIBe2ZiMxxk5L5vGBV9QwaOEGU80LgtA/gEqkiO2IMUBlQJFqvvhh6RSph5lWpLuv\n"
            + "    /Xt7WBzDsZOcxXNffg2+pWNpbpwZdHohlwQEI1OqiVYVnfG4euAkzeWZZLsRUuAjHfcWVIzDoSoK\n"
            + "    wC+gqdUQHBV+pWyn6PXVslS0JIldeegbiwF076M1D7ybeCABXoQelSZRHkx1szO8UnxSR3X7Cemu\n"
            + "    p9De/7z9+WPPclqybINVIPy6Kvl8mHrGSlzawQRDKtoMrJa8bo93PookF8sbg5EoGapV0yNpMEiA\n"
            + "    spq3DEcdXB6mGDGPnLbS2WXq4zjKopASRKkZvOMdgfS6NdUMDtKS1TsOrv2KKTkLnGYfvdAeWiMg\n"
            + "    oFbuyYQ0mnDlLH1UW6anI8RxXn+wmdyZA+/ksapGvRmkvz0Mb997WzqNl7v7UTr0SU3Ws01hFsm6\n"
            + "    lW++MsotkyfpR9mWB8/dqVNVShLmIlt7U/YFVfziYSrVdjcAdIlgJ6Ihxb92liQHOU+Qr1YDOmm1\n"
            + "    JSnhlQVvFxWZG7hm5laNL6lqXz5VV6Gk5IeLtMb8kdHz3zj4ascdldapVPLJIa5741GNNgQNU0nH\n"
            + "    FhAyKk0zN7PbL1/XGWPU+s5lai4HE6JM2CKA7jE7cYrdaDZxbba+9iWzQ4YEBDr5Z3OoloK5dvs=\n"
            + "  </value>\n"
            + "</signature>\n";

    public static final PublicKey CERT_1_PUBLIC_KEY;
    public static final PrivateKey CERT_1_PRIVATE_KEY;

    static {
        try {
            CERT_1_PUBLIC_KEY =
                    SecureBox.decodePublicKey(
                            new byte[] {
                                (byte) 0x04, (byte) 0xb8, (byte) 0x00, (byte) 0x11, (byte) 0x18,
                                (byte) 0x98, (byte) 0x1d, (byte) 0xf0, (byte) 0x6e, (byte) 0xb4,
                                (byte) 0x94, (byte) 0xfe, (byte) 0x86, (byte) 0xda, (byte) 0x1c,
                                (byte) 0x07, (byte) 0x8d, (byte) 0x01, (byte) 0xb4, (byte) 0x3a,
                                (byte) 0xf6, (byte) 0x8d, (byte) 0xdc, (byte) 0x61, (byte) 0xd0,
                                (byte) 0x46, (byte) 0x49, (byte) 0x95, (byte) 0x0f, (byte) 0x10,
                                (byte) 0x86, (byte) 0x93, (byte) 0x24, (byte) 0x66, (byte) 0xe0,
                                (byte) 0x3f, (byte) 0xd2, (byte) 0xdf, (byte) 0xf3, (byte) 0x79,
                                (byte) 0x20, (byte) 0x1d, (byte) 0x91, (byte) 0x55, (byte) 0xb0,
                                (byte) 0xe5, (byte) 0xbd, (byte) 0x7a, (byte) 0x8b, (byte) 0x32,
                                (byte) 0x7d, (byte) 0x25, (byte) 0x53, (byte) 0xa2, (byte) 0xfc,
                                (byte) 0xa5, (byte) 0x65, (byte) 0xe1, (byte) 0xbd, (byte) 0x21,
                                (byte) 0x44, (byte) 0x7e, (byte) 0x78, (byte) 0x52, (byte) 0xfa
                            });
            CERT_1_PRIVATE_KEY =
                    decodePrivateKey(
                            new byte[] {
                                (byte) 0x70, (byte) 0x01, (byte) 0xc7, (byte) 0x87, (byte) 0x32,
                                (byte) 0x2f, (byte) 0x1c, (byte) 0x9a, (byte) 0x6e, (byte) 0xb1,
                                (byte) 0x91, (byte) 0xca, (byte) 0x4e, (byte) 0xb5, (byte) 0x44,
                                (byte) 0xba, (byte) 0xc8, (byte) 0x68, (byte) 0xc6, (byte) 0x0a,
                                (byte) 0x76, (byte) 0xcb, (byte) 0xd3, (byte) 0x63, (byte) 0x67,
                                (byte) 0x7c, (byte) 0xb0, (byte) 0x11, (byte) 0x82, (byte) 0x65,
                                (byte) 0x77, (byte) 0x01
                            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static byte[] getCertPath1Bytes() {
        try {
            return CertUtils.decodeBase64(CERT_PATH_1_BASE64);
        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    public static byte[] getCertPath2Bytes() {
        try {
            return CertUtils.decodeBase64(CERT_PATH_2_BASE64);
        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    public static final CertPath CERT_PATH_1;
    public static final CertPath CERT_PATH_2;

    static {
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            CERT_PATH_1 = certFactory.generateCertPath(
                    new ByteArrayInputStream(getCertPath1Bytes()), CERT_PATH_ENCODING);
            CERT_PATH_2 = certFactory.generateCertPath(
                    new ByteArrayInputStream(getCertPath2Bytes()), CERT_PATH_ENCODING);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] getCertXmlWithSerial(long serial) {
        String xml = THM_CERT_XML_BEFORE_SERIAL + serial + THM_CERT_XML_AFTER_SERIAL;
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] getCertXml() {
        return getCertXmlWithSerial(DEFAULT_SERIAL);
    }

    public static byte[] getSigXml() {
        return THM_SIG_XML.getBytes(StandardCharsets.UTF_8);
    }

    private static PrivateKey decodePrivateKey(byte[] keyBytes) throws Exception {
        assertThat(keyBytes.length).isEqualTo(32);
        BigInteger priv = new BigInteger(/*signum=*/ 1, keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("EC");
        return keyFactory.generatePrivate(new ECPrivateKeySpec(priv, SecureBox.EC_PARAM_SPEC));
    }

    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }
}
