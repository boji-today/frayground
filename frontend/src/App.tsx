import { Flex, Heading, Link, Text } from "@radix-ui/themes";
import LaboratoryPage from "./laboratory/LaboratoryPage";
import { hrefFor, navigate, routeFromPathname } from "./routing/route";
import { useRoute } from "./routing/useRoute";

export default function App() {
  const pathname = useRoute();
  if (routeFromPathname(pathname) === "laboratory") return <LaboratoryPage />;
  return (
    <Flex direction="column" gap="3" p="5">
      <Heading>Frayground</Heading>
      <Text>实验室骨架。对局界面尚未实现。</Text>
      <Link
        href={hrefFor("laboratory")}
        onClick={(event) => {
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return;
          event.preventDefault();
          navigate(hrefFor("laboratory"));
        }}
      >
        实验室
      </Link>
    </Flex>
  );
}
