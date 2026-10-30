import { Button, Flex, Heading, Text, TextArea } from "@radix-ui/themes";
import { canPublishDraft, canSaveDraft, draftStatusLabel } from "./logic/seats";
import type { SeatDraft, SeatKey } from "./types";

export function PromptColumn(props: {
  seatKey: SeatKey;
  draft: SeatDraft | null;
  onChange: (content: string) => void;
  onSave: () => void;
  onPublish: () => void;
  onLoadRemote: () => void;
}) {
  const { draft, seatKey } = props;
  return (
    <Flex direction="column" gap="2">
      <Heading size="4">席位 {seatKey}</Heading>
      {draft?.name ? <Text>{draft.name}</Text> : null}
      {!draft || draft.phase === "loading" ? (
        <>
          <Text>正在加载草稿</Text>
          {draft?.error ? <Text color="red">{draft.error}</Text> : null}
        </>
      ) : (
        <>
          <Text>当前发布 v{draft.publishedVersionNo}</Text>
          <Text>{draftStatusLabel(draft)}</Text>
          <TextArea
            aria-label={`席位 ${seatKey} 草稿`}
            value={draft.content}
            onChange={(event) => props.onChange(event.target.value)}
            rows={14}
          />
          <Flex gap="2">
            <Button type="button" disabled={!canSaveDraft(draft)} onClick={props.onSave}>保存</Button>
            <Button type="button" disabled={!canPublishDraft(draft)} onClick={props.onPublish}>应用</Button>
          </Flex>
          {draft.remote ? (
            <>
              <Text color="red">草稿冲突，远端修订号 {draft.remote.revision}</Text>
              <Button type="button" onClick={props.onLoadRemote}>载入远端</Button>
            </>
          ) : null}
          {draft.error ? <Text color="red">{draft.error}</Text> : null}
        </>
      )}
    </Flex>
  );
}
