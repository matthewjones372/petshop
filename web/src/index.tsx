import { render } from "solid-js/web";
import { Route, Router } from "@solidjs/router";
import "./styles.css";
import Layout from "./Layout";
import Home from "./pages/Home";
import Pets from "./pages/Pets";
import PetPage from "./pages/Pet";
import Stats from "./pages/Stats";
import Live from "./pages/Live";

render(
  () => (
    <Router root={Layout}>
      <Route path="/" component={Home} />
      <Route path="/pets" component={Pets} />
      <Route path="/pets/:id" component={PetPage} />
      <Route path="/stats" component={Stats} />
      <Route path="/live" component={Live} />
    </Router>
  ),
  document.getElementById("root")!,
);
