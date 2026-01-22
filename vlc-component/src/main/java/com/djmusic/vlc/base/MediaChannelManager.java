package com.djmusic.vlc.base;

import uk.co.caprica.vlcj.player.base.MediaPlayer;

public class MediaChannelManager {

  private final MediaPlayer channelA;

  public MediaChannelManager(MediaPlayer a) {
    this.channelA = a;
  }

  public MediaPlayer getMain() {
    return channelA;
  }
}
