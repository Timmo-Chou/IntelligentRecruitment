import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiStream } from "@/lib/api-client";
import { streamJdRunEvents } from "@/lib/recruitment-api";

vi.mock("@/lib/api-client", () => ({
  apiFetch: vi.fn(),
  apiStream: vi.fn(),
}));

describe("streamJdRunEvents", () => {
  beforeEach(() => vi.clearAllMocks());

  it("sends the replay cursor and parses persisted SSE events", async () => {
    const frames = [
      "id: 42\nevent: delta\ndata: {\"delta\":\"正在生成\",\"progress\":50}\n\n",
      "id: 43\nevent: completed\ndata: {\"status\":\"COMPLETED\",\"progress\":100}\n\n",
    ];
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const frame of frames) controller.enqueue(new TextEncoder().encode(frame));
        controller.close();
      },
    });
    vi.mocked(apiStream).mockResolvedValue(new Response(body));
    const events: Array<{ id: number; type: string }> = [];

    await streamJdRunEvents("workspace", "task", 41, event => events.push(event), new AbortController().signal);

    expect(apiStream).toHaveBeenCalledWith(
      "/workspaces/workspace/recruitment-tasks/task/jd-runs/events",
      expect.objectContaining({ headers: { "Last-Event-ID": "41" } }),
    );
    expect(events).toEqual([
      expect.objectContaining({ id: 42, type: "delta" }),
      expect.objectContaining({ id: 43, type: "completed" }),
    ]);
  });
});
