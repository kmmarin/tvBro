// Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
// Which channel to resume when tvBro opens. Pure logic (unit-tested with node).
//
// The device remembers its current channel (localStorage, tvbro.ui) and the server remembers the
// household's (prefs.lastChannel in state.db). The newest of the two wins, so a new or cleared browser,
// or another device, opens on the channel watched last; a device without the server (offline) keeps
// its own.

/** device / server: {key, name, …, at (ms)} or null. Returns {ch, from: 'device'|'server'} or null. */
export function pickLastChannel(device, server) {
  const ok = (c) => !!(c && typeof c === 'object' && typeof c.key === 'string' && c.key);
  if (!ok(device) && !ok(server)) return null;
  if (!ok(server)) return { ch: device, from: 'device' };
  if (!ok(device)) return { ch: server, from: 'server' };
  return (server.at || 0) > (device.at || 0) ? { ch: server, from: 'server' } : { ch: device, from: 'device' };
}
