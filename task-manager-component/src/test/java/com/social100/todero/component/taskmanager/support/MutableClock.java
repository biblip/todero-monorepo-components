package com.social100.todero.component.taskmanager.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public class MutableClock extends Clock {
  private Instant now;
  private final ZoneId zone;

  public MutableClock(Instant now) {
    this(now, ZoneOffset.UTC);
  }

  public MutableClock(Instant now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  public void set(Instant value) {
    this.now = value;
  }

  public void advanceSeconds(long seconds) {
    this.now = this.now.plusSeconds(seconds);
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new MutableClock(now, zone);
  }

  @Override
  public Instant instant() {
    return now;
  }
}
