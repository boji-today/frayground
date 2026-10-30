import { Flex, Text } from "@radix-ui/themes";
import { useLaboratory } from "./useLaboratory";
import { ControlBar } from "./ControlBar";
import { EventList } from "./EventList";
import { PromptColumn } from "./PromptColumn";

export default function LaboratoryPage() {
  const page = useLaboratory();
  return (
    <Flex gap="4" p="4" align="start">
      <Flex direction="column" style={{ flex: "1 1 16rem", minWidth: 0 }}>
        <PromptColumn
          seatKey="A"
          draft={page.seats.A}
          onChange={(content) => page.editSeat("A", content)}
          onSave={() => void page.saveSeat("A")}
          onPublish={() => void page.publishSeat("A")}
          onLoadRemote={() => page.loadRemote("A")}
        />
      </Flex>
      <Flex direction="column" gap="3" style={{ flex: "1.4 1 20rem", minWidth: 0 }}>
        {page.pageError ? <Text color="red">{page.pageError}</Text> : null}
        <ControlBar
          room={page.room}
          sse={page.sse}
          commandError={page.commandError}
          commandUncertain={page.commandUncertain}
          gaveUp={page.sseGaveUp}
          availability={page.availability}
          acknowledge={page.acknowledge}
          onCommand={(type) => void page.sendControl(type)}
          onReconnect={page.reconnect}
        />
        <EventList events={page.events} />
      </Flex>
      <Flex direction="column" style={{ flex: "1 1 16rem", minWidth: 0 }}>
        <PromptColumn
          seatKey="B"
          draft={page.seats.B}
          onChange={(content) => page.editSeat("B", content)}
          onSave={() => void page.saveSeat("B")}
          onPublish={() => void page.publishSeat("B")}
          onLoadRemote={() => page.loadRemote("B")}
        />
      </Flex>
    </Flex>
  );
}
