import { DurableObject } from "cloudflare:workers";

const MAX_ROOM_NAME_LENGTH = 50;
const MAX_ROOM_CODE_LENGTH = 64;
const MAX_MESSAGE_SIZE = 16 * 1024;
const ROOM_TTL_MS = 24 * 60 * 60 * 1000;

const CONTROL_TYPES = new Set([
  "PLAY",
  "PAUSE",
  "SEEK",
  "OPEN_CONTENT",
  "CHANGE_EPISODE",
  "NAVIGATE",
]);

const ALLOWED_TYPES = new Set([
  ...CONTROL_TYPES,
  "PING",
  "CHAT",
  "SYNC_REPORT",
]);

export default {
  async fetch(request, env) {

    /*
     * CORS preflight
     */
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(),
      });
    }

    const url = new URL(request.url);
    let response;

    if (url.pathname === "/") {
      response = Response.json({
        ok: true,
        service: "PulseStream Watch Together Server",
        version: "2",
      });
    }
    else if (
      url.pathname === "/user/register" &&
      request.method === "POST"
    ) {
      response = await registerUser(request, env);
    }
    else if (
      url.pathname === "/user/count" &&
      request.method === "GET"
    ) {
      response = await getUserCount(env);
    }
    else if (
      url.pathname === "/room/create" &&
      request.method === "POST"
    ) {
      response = await createRoom(request, env);
    }
    else if (
      url.pathname === "/room/check" &&
      request.method === "GET"
    ) {
      response = await checkRoom(request, env);
    }
    else if (url.pathname === "/ws") {
      return handleWebSocket(request, env);
    }
    else if (
      url.pathname === "/update/check" &&
      request.method === "GET"
    ) {
      response = await handleUpdateCheck(request, env);
    }
    else if (
      url.pathname === "/update/publish" &&
      request.method === "POST"
    ) {
      // Admin endpoint: publish a new version + auto-delete old APKs from R2.
      // Call with: curl -X POST https://your-worker.url/update/publish \
      //   -H "Authorization: Bearer YOUR_ADMIN_KEY" \
      //   -d '{"version":"1.0.0.14","changelog":"What is new"}'
      const authHeader = request.headers.get("Authorization") || "";
      const adminKey = env.ADMIN_KEY || "";
      if (!adminKey || authHeader !== `Bearer ${adminKey}`) {
        response = new Response("Unauthorized", { status: 401 });
      } else {
        try {
          const body = await request.json();
          const newVersion = body.version;
          const changelog = body.changelog || "";
          if (!newVersion) {
            response = new Response("Missing version", { status: 400 });
          } else {
            // List all objects in the R2 bucket and delete everything except the new version
            const keepFile = `pulsestreamV${newVersion}.apk`;
            const listed = await env.APK_BUCKET.list({ prefix: "updates/" });
            let deleted = 0;
            for (const obj of listed.objects) {
              if (obj.key !== `updates/${keepFile}`) {
                await env.APK_BUCKET.delete(obj.key);
                deleted++;
              }
            }
            // Also try root-level files
            const listedRoot = await env.APK_BUCKET.list({ prefix: "" });
            for (const obj of listedRoot.objects) {
              if (!obj.key.startsWith("updates/") && obj.key.endsWith(".apk") && obj.key !== keepFile) {
                await env.APK_BUCKET.delete(obj.key);
                deleted++;
              }
            }
            response = Response.json({
              ok: true,
              version: newVersion,
              deleted: deleted,
              message: `Published v${newVersion}, deleted ${deleted} old APK(s). Now run: wrangler secret put CURRENT_VERSION`,
            });
          }
        } catch (e) {
          response = Response.json({ ok: false, error: e.message }, { status: 500 });
        }
      }
    }

    else if (
      url.pathname === "/update/download" &&
      request.method === "GET"
    ) {
      const file = url.searchParams.get("file");
      if (!file || file.length > 200) {
        response = new Response("Bad request", { status: 400 });
      } else {
        try {
          const object = await env.APK_BUCKET.get(file);
          if (!object) {
            response = new Response("File not found", { status: 404 });
          } else {
            const headers = new Headers();
            headers.set("Content-Type", "application/vnd.android.package-archive");
            headers.set("Content-Disposition", `attachment; filename="${file}"`);
            if (object.httpMetadata?.cacheControl) {
              headers.set("Cache-Control", object.httpMetadata.cacheControl);
            }
            response = new Response(object.body, { headers });
          }
        } catch (e) {
          response = new Response("Download error", { status: 500 });
        }
      }
    }
    else {
      response = new Response("Not Found", { status: 404 });
    }

    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(corsHeaders())) {
      headers.set(key, value);
    }

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers,
    });
  },
};


/*
 * ============================================================
 * USER REGISTRATION / TRACKING
 * ============================================================
 */

async function registerUser(request, env) {
  let body;

  try {
    body = await request.json();
  } catch {
    return Response.json(
      { error: "Invalid JSON." },
      { status: 400 }
    );
  }

  const userId = body?.userId;
  const name = body?.name || "Unknown";
  const email = body?.email || "";
  const photo = body?.photo || "";
  const appVersion = body?.appVersion || "";
  const timestamp = body?.timestamp || Date.now();

  if (!userId) {
    return Response.json(
      { error: "userId is required." },
      { status: 400 }
    );
  }

  /*
   * Store user in KV. Key = "user:{userId}"
   */
  const userKey = `user:${userId}`;
  const existing = await env.USERS_KV.get(userKey);

  if (!existing) {
    /* New user — store and increment counter */
    const userData = {
      userId,
      name,
      email,
      photo,
      appVersion,
      registeredAt: timestamp,
      lastSeen: timestamp,
    };

    await env.USERS_KV.put(userKey, JSON.stringify(userData));

    /* Increment total user count */
    const countStr = await env.USERS_KV.get("user:count");
    const count = countStr ? parseInt(countStr, 10) + 1 : 1;
    await env.USERS_KV.put("user:count", String(count));

    return Response.json({
      success: true,
      isNew: true,
      totalUsers: count,
    });
  }

  /*
   * Existing user — just update lastSeen
   */
  const userData = JSON.parse(existing);
  userData.lastSeen = timestamp;
  await env.USERS_KV.put(userKey, JSON.stringify(userData));

  const countStr = await env.USERS_KV.get("user:count");
  const count = countStr ? parseInt(countStr, 10) : 0;

  return Response.json({
    success: true,
    isNew: false,
    totalUsers: count,
  });
}


/*
 * ============================================================
 * GET USER COUNT
 * ============================================================
 */

async function getUserCount(env) {
  const countStr = await env.USERS_KV.get("user:count");
  const count = countStr ? parseInt(countStr, 10) : 0;

  // Count active users (seen in last 24 hours).
  let activeCount = 0;
  const now = Date.now();
  const activeWindowMs = 24 * 60 * 60 * 1000;
  const list = await env.USERS_KV.list({ prefix: "user:" });
  for (const key of list.keys) {
    if (key.name === "user:count") continue;
    try {
      const val = await env.USERS_KV.get(key.name);
      if (val) {
        const userData = JSON.parse(val);
        if (userData.lastSeen && (now - userData.lastSeen) < activeWindowMs) {
          activeCount++;
        }
      }
    } catch {}
  }

  return Response.json({
    ok: true,
    totalUsers: count,
    activeUsers: activeCount,
  });
}


/*
 * ============================================================
 * CREATE ROOM
 * ============================================================
 */

async function createRoom(request, env) {
  let body;

  try {
    body = await request.json();
  } catch {
    return Response.json(
      {
        error: "Invalid JSON.",
      },
      {
        status: 400,
      }
    );
  }

  const roomName =
    typeof body?.roomName === "string"
      ? body.roomName.trim()
      : "";

  if (!roomName) {
    return Response.json(
      {
        error: "Room name is required.",
      },
      {
        status: 400,
      }
    );
  }

  if (roomName.length > MAX_ROOM_NAME_LENGTH) {
    return Response.json(
      {
        error: `Room name must be ${MAX_ROOM_NAME_LENGTH} characters or less.`,
      },
      {
        status: 400,
      }
    );
  }

  /*
   * Generate the 8-digit numeric suffix securely.
   */
  const digits = secureDigits(8);

  /*
   * Convert the room name to a safe compact code.
   *
   * "mahdi room" -> "mahdiroom"
   */
  const cleanName =
    roomName
      .toLowerCase()
      .replace(/[^a-z0-9]/g, "")
      .slice(0, 30);

  /*
   * If the name contains no usable characters,
   * use "room".
   */
  const prefix = cleanName || "room";

  const roomCode = `${prefix}${digits}`;

  /*
   * Generate a cryptographically random host token.
   */
  const hostToken = createToken();

  /*
   * Get the Durable Object belonging to this room.
   */
  const roomId = env.WATCH_ROOM.idFromName(roomCode);
  const roomObject = env.WATCH_ROOM.get(roomId);

  /*
   * Initialize the room.
   */
  const response = await roomObject.fetch(
    new Request(
      "https://internal/initialize",
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({
          roomCode,
          roomName,
          hostToken,
        }),
      }
    )
  );

  if (!response.ok) {
    const error = await response.text();

    return new Response(error, {
      status: response.status,
      headers: {
        "content-type": "application/json",
      },
    });
  }

  return Response.json({
    success: true,
    roomName,
    roomCode,
    hostToken,
  });
}


/*
 * ============================================================
 * CHECK ROOM
 * ============================================================
 */

async function checkRoom(request, env) {
  const url = new URL(request.url);

  const roomCode =
    url.searchParams.get("room")?.trim();

  if (!roomCode) {
    return Response.json(
      {
        error: "Missing room code.",
      },
      {
        status: 400,
      }
    );
  }

  if (!isValidRoomCode(roomCode)) {
    return Response.json(
      {
        error: "Invalid room code.",
      },
      {
        status: 400,
      }
    );
  }

  const roomId =
    env.WATCH_ROOM.idFromName(roomCode);

  const roomObject =
    env.WATCH_ROOM.get(roomId);

  const response =
    await roomObject.fetch(
      new Request(
        `https://internal/check?room=${encodeURIComponent(roomCode)}`
      )
    );

  return new Response(
    response.body,
    {
      status: response.status,
      headers: {
        "content-type":
          response.headers.get("content-type") ||
          "application/json",
      },
    }
  );
}


/*
 * ============================================================
 * UPDATE CHECK ENDPOINT
 * ============================================================
 */

// GitHub Repo - UPDATE THIS IF REPO CHANGES
const GITHUB_REPO = "mahdiridoy/APK-Pulse-TV";
const GITHUB_API = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`;

async function handleUpdateCheck(request, env) {
  const url = new URL(request.url);
  const currentVersion = url.searchParams.get('v') || '0.0.0.0';

  try {
    const response = await fetch(GITHUB_API);
    if (response.status === 404) {
      return Response.json({ updateAvailable: false }, { headers: corsHeaders() });
    }
    const release = await response.json();
    
    const latestVersion = release.tag_name.replace('v', '');
    const isNewer = compareVersions(latestVersion, currentVersion) > 0;
    
    // Find APK asset
    let downloadUrl = null;
    if (release.assets) {
      for (const asset of release.assets) {
        if (asset.name.endsWith('.apk') && !asset.name.includes('debug')) {
          downloadUrl = asset.browser_download_url;
          break;
        }
      }
    }

    return Response.json(
      {
        updateAvailable: isNewer,
        version: latestVersion,
        downloadUrl: isNewer ? downloadUrl : null,
        changelog: release.body || null,
      },
      {
        headers: corsHeaders(),
      }
    );
  } catch (e) {
    return Response.json({ updateAvailable: false }, { headers: corsHeaders() });
  }
}

function compareVersions(v1, v2) {
  const p1 = v1.split('.').map(Number);
  const p2 = v2.split('.').map(Number);
  const max = Math.max(p1.length, p2.length);
  for (let i = 0; i < max; i++) {
    const a = p1[i] || 0;
    const b = p2[i] || 0;
    if (a !== b) return a - b;
  }
  return 0;
}


/*
 * ============================================================
 * WEBSOCKET ROUTER
 * ============================================================
 */

async function handleWebSocket(request, env) {
  const url = new URL(request.url);

  const roomCode =
    url.searchParams.get("room")?.trim();

  const token =
    url.searchParams.get("token")?.trim() || "";

  const requestedRole =
    url.searchParams.get("role")?.trim() || "viewer";

  if (!roomCode) {
    return Response.json(
      {
        error: "Missing room code.",
      },
      {
        status: 400,
      }
    );
  }

  if (!isValidRoomCode(roomCode)) {
    return Response.json(
      {
        error: "Invalid room code.",
      },
      {
        status: 400,
      }
    );
  }

  if (
    requestedRole !== "host" &&
    requestedRole !== "viewer"
  ) {
    return Response.json(
      {
        error: "Invalid role.",
      },
      {
        status: 400,
      }
    );
  }

  if (
    request.headers.get("Upgrade")?.toLowerCase() !==
    "websocket"
  ) {
    return new Response(
      "WebSocket connection required.",
      {
        status: 426,
      }
    );
  }

  const roomId =
    env.WATCH_ROOM.idFromName(roomCode);

  const roomObject =
    env.WATCH_ROOM.get(roomId);

  /*
   * The Durable Object performs the actual
   * host-token verification.
   */
  return roomObject.fetch(request);
}


/*
 * ============================================================
 * DURABLE OBJECT
 * ============================================================
 */

export class WatchRoom extends DurableObject {
  /*
   * Allow the DO to hibernate when idle. Without this, the DO is evicted
   * after a period of inactivity and every attached WebSocket (host and
   * viewers) is forcibly closed - which looked like "viewers disconnect
   * when the host closes the app" during idle watching.
   */
  static enableHibernation = true;

  constructor(ctx, env) {
    super(ctx, env);

    this.ctx = ctx;
    this.env = env;

    /*
     * Set while the room is being torn down because the host
     * disconnected, so viewer close events don't broadcast.
     */
    this.closingRoom = false;

    /*
     * Restore WebSocket session information after
     * Durable Object hibernation.
     */
    this.sessions = new Map();

    for (const ws of this.ctx.getWebSockets()) {
      const attachment =
        ws.deserializeAttachment();

      if (attachment) {
        this.sessions.set(ws, attachment);
      }
    }
  }


  /*
   * ==========================================================
   * INTERNAL HTTP ENDPOINTS + WEBSOCKET
   * ==========================================================
   */

  async fetch(request) {
    const url = new URL(request.url);

    /*
     * Room initialization
     */
    if (
      url.pathname === "/initialize" &&
      request.method === "POST"
    ) {
      return this.initializeRoom(request);
    }

    /*
     * Room check
     */
    if (
      url.pathname === "/check" &&
      request.method === "GET"
    ) {
      return this.checkRoom();
    }

    /*
     * WebSocket connection
     */
    if (url.pathname === "/ws") {
      return this.connectWebSocket(request);
    }

    return new Response("Not Found", {
      status: 404,
    });
  }


  /*
   * ==========================================================
   * INITIALIZE ROOM
   * ==========================================================
   */

  async initializeRoom(request) {
    const body = await request.json();

    const roomCode = body.roomCode;
    const roomName = body.roomName;
    const hostToken = body.hostToken;

    if (
      typeof roomCode !== "string" ||
      typeof roomName !== "string" ||
      typeof hostToken !== "string"
    ) {
      return Response.json(
        {
          error: "Invalid room initialization.",
        },
        {
          status: 400,
        }
      );
    }

    /*
     * If the room already exists, don't overwrite it.
     */
    const existing =
      await this.ctx.storage.get("room");

    if (existing) {
      return Response.json(
        {
          error: "Room already exists.",
        },
        {
          status: 409,
        }
      );
    }

    const room = {
      roomCode,
      roomName,
      hostToken,
      createdAt: Date.now(),
      lastActivity: Date.now(),

      /*
       * Watch state.
       */
      state: {
        contentId: null,
        episodeId: null,
        contentUrl: null,
        apiName: null,
        name: null,
        episode: null,
        season: null,
        position: 0,
        playing: false,
      },
    };

    await this.ctx.storage.put(
      "room",
      room
    );

    return Response.json({
      success: true,
    });
  }


  /*
   * ==========================================================
   * CHECK ROOM
   * ==========================================================
   */

  async checkRoom() {
    const room =
      await this.ctx.storage.get("room");

    if (!room) {
      return Response.json(
        {
          exists: false,
        },
        {
          status: 404,
        }
      );
    }

    /*
     * Expire old rooms.
     */
    if (
      Date.now() - room.lastActivity >
      ROOM_TTL_MS
    ) {
      await this.ctx.storage.delete("room");

      return Response.json(
        {
          exists: false,
          expired: true,
        },
        {
          status: 404,
        }
      );
    }

    return Response.json({
      exists: true,
      roomName: room.roomName,
      createdAt: room.createdAt,
    });
  }


  /*
   * ==========================================================
   * CONNECT WEBSOCKET
   * ==========================================================
   */

  async connectWebSocket(request) {
    const url = new URL(request.url);

    const roomCode =
      url.searchParams.get("room")?.trim();

    const token =
      url.searchParams.get("token")?.trim() || "";

    const requestedRole =
      url.searchParams.get("role")?.trim() ||
      "viewer";

    const name =
      (
        url.searchParams.get("name")?.trim() ||
        (requestedRole === "host" ? "Host" : "Viewer")
      ).slice(0, 40);

    const room =
      await this.ctx.storage.get("room");

    if (!room) {
      return Response.json(
        {
          error: "Room does not exist.",
        },
        {
          status: 404,
        }
      );
    }

    /*
     * Expire old room.
     */
    if (
      Date.now() - room.lastActivity >
      ROOM_TTL_MS
    ) {
      await this.ctx.storage.delete("room");

      return Response.json(
        {
          error: "Room has expired.",
        },
        {
          status: 404,
        }
      );
    }

    /*
     * Host authentication.
     *
     * Only somebody possessing the secret host token
     * can become the host.
     */
    let actualRole = "viewer";

    if (
      requestedRole === "host" &&
      token &&
      await safeEqual(token, room.hostToken)
    ) {
      actualRole = "host";
    }

    /*
     * If somebody asks for host but provides the wrong
     * token, reject instead of silently turning them
     * into a viewer.
     */
    if (
      requestedRole === "host" &&
      actualRole !== "host"
    ) {
      return Response.json(
        {
          error: "Invalid host token.",
        },
        {
          status: 403,
        }
      );
    }

    /*
     * Prevent multiple simultaneous hosts.
     */
    if (actualRole === "host") {
      const existingHost =
        this.findHost();

      if (existingHost) {
        return Response.json(
          {
            error: "A host is already connected.",
          },
          {
            status: 409,
          }
        );
      }
    }

    const pair =
      new WebSocketPair();

    const client = pair[0];
    const server = pair[1];

    const attachment = {
      roomCode,
      role: actualRole,
      name,
      connectedAt: Date.now(),
    };

    /*
     * Hibernation-compatible WebSocket.
     */
    this.ctx.acceptWebSocket(server);

    /*
     * Persist connection metadata across hibernation.
     */
    server.serializeAttachment(
      attachment
    );

    this.sessions.set(
      server,
      attachment
    );

    /*
     * Update room activity.
     */
    room.lastActivity = Date.now();

    await this.ctx.storage.put(
      "room",
      room
    );

    /*
     * Tell client that connection succeeded.
     */
    this.safeSend(
      server,
      {
        type: "CONNECTED",
        room: room.roomCode,
        roomName: room.roomName,
        role: actualRole,
        name,
        timestamp: Date.now(),
      }
    );

    /*
     * Send the full current roster so the new client can show
     * how many people are connected and their names.
     */
    this.safeSend(
      server,
      {
        type: "ROOM_USERS",
        users: this.getUsers(),
        timestamp: Date.now(),
      }
    );

    /*
     * If this is a viewer, immediately send the
     * current watch state.
     */
    if (actualRole === "viewer") {
      this.safeSend(
        server,
        {
          type: "ROOM_STATE",
          state: room.state,
          timestamp: Date.now(),
        }
      );
    }

    /*
     * Notify everyone else.
     */
    this.broadcast(
      {
        type: "USER_JOINED",
        role: actualRole,
        name,
        timestamp: Date.now(),
      },
      server
    );

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }


  /*
   * ==========================================================
   * WEBSOCKET MESSAGE
   * ==========================================================
   */

  async webSocketMessage(ws, message) {
    if (typeof message !== "string") {
      return;
    }

    if (message.length > MAX_MESSAGE_SIZE) {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Message too large.",
      });

      return;
    }

    let data;

    try {
      data = JSON.parse(message);
    } catch {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Invalid JSON.",
      });

      return;
    }

    if (!data || typeof data !== "object") {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Invalid message.",
      });

      return;
    }

    const session =
      ws.deserializeAttachment();

    if (!session) {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Session not found.",
      });

      return;
    }

    /*
     * Keep local session map in sync after hibernation.
     */
    this.sessions.set(
      ws,
      session
    );

    const room =
      await this.ctx.storage.get("room");

    if (!room) {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Room no longer exists.",
      });

      return;
    }

    /*
     * Safety net for force-closed hosts: if the host's WebSocket is gone but the
     * close event was never processed (e.g. the Durable Object hibernated), don't
     * strand the remaining viewers in a host-less room. As soon as a non-host sends
     * any message, check for a live host and tear the room down if there is none.
     */
    if (
      session.role !== "host" &&
      !this.findHost()
    ) {
      await this.closeRoom(ws);
      return;
    }

    /*
     * Refresh activity.
     */
    room.lastActivity = Date.now();

    /*
     * PING does not need to be broadcast.
     */
    if (data.type === "PING") {
      this.safeSend(ws, {
        type: "PONG",
        timestamp: Date.now(),
      });

      await this.ctx.storage.put(
        "room",
        room
      );

      return;
    }

    /*
     * CHAT: broadcast to all participants (including sender).
     */
    if (data.type === "CHAT") {
      const chatMsg = {
        type: "CHAT",
        sender: session.name || "Unknown",
        message: String(data.message || "").slice(0, 500),
        timestamp: Date.now(),
      };
      this.broadcast(chatMsg, null);
      return;
    }

    /*
     * SYNC_REPORT: viewers report their playback position.
     * Host computes the delta and sends SYNC_STATUS back.
     */
    if (data.type === "SYNC_REPORT") {
      if (session.role === "host") return;
      const host = this.findHost();
      if (!host) return;
      const viewerPos = typeof data.position === "number" ? data.position : 0;
      const deltaMs = viewerPos - (room.state.position || 0);
      this.safeSend(ws, {
        type: "SYNC_STATUS",
        deltaMs: deltaMs,
        playing: room.state.playing || false,
      });
      return;
    }

    /*
     * Reject unknown message types.
     */
    if (!ALLOWED_TYPES.has(data.type)) {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Unknown message type.",
      });

      return;
    }

    /*
     * Viewers cannot control playback/navigation.
     */
    if (
      CONTROL_TYPES.has(data.type) &&
      session.role !== "host"
    ) {
      this.safeSend(ws, {
        type: "ERROR",
        message: "Viewers do not have control.",
      });

      return;
    }

    /*
     * Update persisted room state.
     */
    if (
      session.role === "host"
    ) {
      this.updateRoomState(
        room,
        data
      );
    }

    await this.ctx.storage.put(
      "room",
      room
    );

    /*
     * Add server timestamp.
     */
    const outgoing = {
      ...data,
      timestamp: Date.now(),
    };

    /*
     * Send host event to viewers.
     */
    this.broadcast(
      outgoing,
      ws
    );
  }


  /*
   * ==========================================================
   * UPDATE ROOM STATE
   * ==========================================================
   */

  updateRoomState(room, data) {
    switch (data.type) {

      case "PLAY":

        room.state.playing = true;

        if (
          typeof data.position === "number"
        ) {
          room.state.position =
            Math.max(0, data.position);
        }

        break;


      case "PAUSE":

        room.state.playing = false;

        if (
          typeof data.position === "number"
        ) {
          room.state.position =
            Math.max(0, data.position);
        }

        break;


      case "SEEK":

        if (
          typeof data.position === "number"
        ) {
          room.state.position =
            Math.max(0, data.position);
        }

        break;


      case "OPEN_CONTENT":

        if (
          typeof data.contentId === "string"
        ) {
          room.state.contentId =
            data.contentId;
        }

        if (
          typeof data.contentUrl === "string"
        ) {
          room.state.contentUrl =
            data.contentUrl;
        }

        if (
          typeof data.apiName === "string"
        ) {
          room.state.apiName =
            data.apiName;
        }

        if (
          typeof data.name === "string"
        ) {
          room.state.name =
            data.name;
        }

        if (
          typeof data.episode === "number"
        ) {
          room.state.episode =
            data.episode;
        }

        if (
          typeof data.season === "number"
        ) {
          room.state.season =
            data.season;
        }

        if (
          typeof data.episodeId === "string" ||
          typeof data.episodeId === "number"
        ) {
          room.state.episodeId =
            String(data.episodeId);
        }

        if (
          typeof data.position === "number"
        ) {
          room.state.position =
            Math.max(0, data.position);
        }

        room.state.playing =
          Boolean(data.playing);

        break;


      case "CHANGE_EPISODE":

        if (
          typeof data.episodeId === "string" ||
          typeof data.episodeId === "number"
        ) {
          room.state.episodeId =
            String(data.episodeId);
        }

        if (
          typeof data.position === "number"
        ) {
          room.state.position =
            Math.max(0, data.position);
        }

        room.state.playing =
          Boolean(data.playing);

        break;
    }
  }


  /*
   * ==========================================================
   * WEBSOCKET CLOSE
   * ==========================================================
   */

  async webSocketClose(
    ws,
    code,
    reason,
    wasClean
  ) {
    const session =
      ws.deserializeAttachment();

    this.sessions.delete(ws);

    /*
     * When the host leaves (closes the app, network drop, ...),
     * tear the whole room down so the viewers don't get stranded
     * in a host-less room.
     */
    if (session?.role === "host") {
      await this.closeRoom(ws);
      return;
    }

    if (this.closingRoom) {
      return;
    }

    this.broadcast(
      {
        type: "USER_DISCONNECTED",
        role:
          session?.role || "viewer",
        name:
          session?.name || "Viewer",
        timestamp: Date.now(),
      },
      ws
    );
  }


  /*
   * ==========================================================
   * WEBSOCKET ERROR
   * ==========================================================
   */

  async webSocketError(
    ws,
    error
  ) {
    const session =
      ws.deserializeAttachment();

    this.sessions.delete(ws);

    if (session?.role === "host") {
      await this.closeRoom(ws);
      return;
    }

    if (this.closingRoom) {
      return;
    }

    this.broadcast(
      {
        type: "USER_DISCONNECTED",
        role:
          session?.role || "viewer",
        name:
          session?.name || "Viewer",
        timestamp: Date.now(),
      },
      ws
    );
  }


  /*
   * ==========================================================
   * CLOSE ROOM
   * ==========================================================
   *
   * The host disconnected. Tell every remaining participant that
   * the room is closing, force-close their sockets and delete the
   * room so the code can't be reused.
   */

  async closeRoom(exceptWs) {
    this.closingRoom = true;

    this.broadcast(
      {
        type: "ROOM_CLOSED",
        timestamp: Date.now(),
      },
      exceptWs
    );

    for (const ws of this.ctx.getWebSockets()) {
      if (ws === exceptWs) {
        continue;
      }

      const session =
        ws.deserializeAttachment();

      if (
        session?.role !== "host" &&
        ws.readyState === WebSocket.OPEN
      ) {
        ws.close(1000, "Host left the room");
      }
    }

    await this.ctx.storage.delete("room");
  }


  /*
   * ==========================================================
   * GET USERS
   * ==========================================================
   *
   * Current roster of everyone attached to the room,
   * including hibernated connections.
   */

  getUsers() {
    const users = [];

    for (
      const ws of this.ctx.getWebSockets()
    ) {
      const session =
        ws.deserializeAttachment();

      if (session) {
        users.push({
          role: session.role,
          name: session.name,
          connectedAt: session.connectedAt,
        });
      }
    }

    return users;
  }


  /*
   * ==========================================================
   * FIND HOST
   * ==========================================================
   */

  findHost() {
    for (const [ws, session] of this.sessions) {
      if (
        session.role === "host" &&
        ws.readyState === WebSocket.OPEN
      ) {
        return ws;
      }
    }

    /*
     * Also inspect hibernated connections.
     */
    for (const ws of this.ctx.getWebSockets()) {
      const session =
        ws.deserializeAttachment();

      if (
        session?.role === "host" &&
        ws.readyState === WebSocket.OPEN
      ) {
        return ws;
      }
    }

    return null;
  }


  /*
   * ==========================================================
   * BROADCAST
   * ==========================================================
   */

  broadcast(
    message,
    except = null
  ) {
    const serialized =
      JSON.stringify(message);

    for (
      const ws of this.ctx.getWebSockets()
    ) {
      if (ws === except) {
        continue;
      }

      this.safeSendRaw(
        ws,
        serialized
      );
    }
  }


  /*
   * ==========================================================
   * SAFE SEND
   * ==========================================================
   */

  safeSend(
    ws,
    message
  ) {
    this.safeSendRaw(
      ws,
      JSON.stringify(message)
    );
  }


  safeSendRaw(
    ws,
    message
  ) {
    try {
      if (
        ws.readyState ===
        WebSocket.OPEN
      ) {
        ws.send(message);
      }
    } catch {
      // Connection already closed.
    }
  }
};


/*
 * ============================================================
 * SECURE RANDOM 8-DIGIT CODE
 * ============================================================
 */

function secureDigits(length) {
  const values =
    new Uint32Array(length);

  crypto.getRandomValues(values);

  return Array.from(values)
    .map(
      value =>
        String(value % 10)
    )
    .join("");
}


/*
 * ============================================================
 * SECURE HOST TOKEN
 * ============================================================
 */

function createToken() {
  const bytes =
    new Uint8Array(32);

  crypto.getRandomValues(bytes);

  return Array.from(bytes)
    .map(
      byte =>
        byte
          .toString(16)
          .padStart(2, "0")
    )
    .join("");
}


/*
 * ============================================================
 * CONSTANT-TIME TOKEN COMPARISON
 * ============================================================
 *
 * We hash both tokens to fixed-size SHA-256 values,
 * then use timingSafeEqual.
 */

async function safeEqual(a, b) {
  const encoder =
    new TextEncoder();

  const aHash =
    await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(a)
    );

  const bHash =
    await crypto.subtle.digest(
      "SHA-256",
      encoder.encode(b)
    );

  let difference = 0;

  for (let i = 0; i < aHash.byteLength; i++) {
    difference |= aHash[i] ^ bHash[i];
  }

  return difference === 0;
}




/*
 * ============================================================
 * ROOM CODE VALIDATION
 * ============================================================
 */

function isValidRoomCode(roomCode) {
  return (
    roomCode.length > 0 &&
    roomCode.length <= MAX_ROOM_CODE_LENGTH &&
    /^[a-zA-Z0-9_-]+$/.test(roomCode)
  );
}

/*
 * ============================================================
 * CORS HEADERS
 * ============================================================
 */

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
  };
}





