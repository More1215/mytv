// Relais du contrôle à distance MyTV.
//
// Une « salle » par code d'accès : la TV (role=tv) et le PC (role=pc) s'y connectent en WebSocket
// et le relais recopie les messages de l'un vers l'autre. Les messages sont chiffrés de bout en
// bout avec une clé tirée du code (AES-GCM) : le relais ne voit que des octets illisibles et ne
// conserve rien. Le nom de la salle est un hachage du code, il ne permet pas de le retrouver.
import { DurableObject } from 'cloudflare:workers';
import PAGE from './page.html';

const ROOM_RE = /^[0-9a-f]{32}$/;
const MAX_MESSAGE = 1024 * 1024; // une image d'écran fait ~50 Ko

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/ws') {
      const room = url.searchParams.get('room') || '';
      const role = url.searchParams.get('role');
      if (!ROOM_RE.test(room) || (role !== 'tv' && role !== 'pc')) return new Response('Bad request', { status: 400 });
      if (request.headers.get('Upgrade') !== 'websocket') return new Response('WebSocket attendu', { status: 426 });
      return env.ROOM.get(env.ROOM.idFromName(room)).fetch(request);
    }
    if (url.pathname === '/' || url.pathname === '/index.html') {
      return new Response(PAGE, {
        headers: {
          'content-type': 'text/html; charset=utf-8',
          'cache-control': 'no-cache',
          'referrer-policy': 'no-referrer',
          'x-frame-options': 'DENY',
          'content-security-policy': "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src blob:; connect-src 'self'",
        },
      });
    }
    return new Response('Not found', { status: 404 });
  },
};

export class Room extends DurableObject {
  async fetch(request) {
    const role = new URL(request.url).searchParams.get('role');
    const other = role === 'tv' ? 'pc' : 'tv';
    // Une seule TV et un seul PC par salle : une nouvelle connexion remplace l'ancienne.
    for (const ws of this.ctx.getWebSockets(role)) ws.close(4000, 'replaced');
    const [client, server] = Object.values(new WebSocketPair());
    // « Hibernation » : la salle ne coûte rien tant qu'aucun message ne passe.
    this.ctx.acceptWebSocket(server, [role]);
    const peerHere = this.ctx.getWebSockets(other).length > 0;
    server.send(JSON.stringify({ relay: 'peer', up: peerHere }));
    for (const ws of this.ctx.getWebSockets(other)) ws.send(JSON.stringify({ relay: 'peer', up: true }));
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    // Seuls les messages binaires (chiffrés) sont relayés ; le texte est réservé au relais.
    if (typeof message === 'string' || message.byteLength > MAX_MESSAGE) return;
    const [role] = this.ctx.getTags(ws);
    const other = role === 'tv' ? 'pc' : 'tv';
    for (const peer of this.ctx.getWebSockets(other)) {
      try { peer.send(message); } catch (_) { /* pair déjà parti */ }
    }
  }

  webSocketClose(ws, code) {
    const [role] = this.ctx.getTags(ws);
    if (code === 4000) return; // remplacé : le nouveau a déjà prévenu l'autre côté
    const other = role === 'tv' ? 'pc' : 'tv';
    for (const peer of this.ctx.getWebSockets(other)) {
      try { peer.send(JSON.stringify({ relay: 'peer', up: false })); } catch (_) {}
    }
  }

  webSocketError(ws) { this.webSocketClose(ws, 1011); }
}
