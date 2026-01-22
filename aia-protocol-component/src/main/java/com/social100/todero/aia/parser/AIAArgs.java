package com.social100.todero.aia.parser;

import com.social100.todero.common.config.ServerType;
import lombok.Getter;

@Getter
public class AIAArgs {
  final ServerType scheme;              // ai | aia
  final String serverHostForSocket; // for InetSocketAddress (IPv6 unbracketed)
  final String serverRawHost;       // for logging (may be same as above)
  final String vhostSni;            // SNI override or derived from URL
  final int port;
  final boolean tlsEnabled;         // true for ai:// or aia://, false for uai:// or uaia://
  final String trustAnchorsPath;    // optional PEM/DER file of trust anchors
  final String pinnedSpkiSha256;    // optional hex/base64 SPKI SHA-256 pin
  final String clientPkcs12Path;   // optional PKCS#12 for client auth
  final String clientPkcs12Password;
  final String clientKeyAlias;

  public AIAArgs(ServerType scheme, String serverHostForSocket, String serverRawHost, String vhostSni, int port,
                 boolean tlsEnabled, String trustAnchorsPath, String pinnedSpkiSha256,
                 String clientPkcs12Path, String clientPkcs12Password, String clientKeyAlias) {
    this.scheme = scheme;
    this.serverHostForSocket = serverHostForSocket;
    this.serverRawHost = serverRawHost;
    this.port = port;
    this.vhostSni = vhostSni;
    this.tlsEnabled = tlsEnabled;
    this.trustAnchorsPath = trustAnchorsPath;
    this.pinnedSpkiSha256 = pinnedSpkiSha256;
    this.clientPkcs12Path = clientPkcs12Path;
    this.clientPkcs12Password = clientPkcs12Password;
    this.clientKeyAlias = clientKeyAlias;
  }
}
