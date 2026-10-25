import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  runApp(const RemoteApp());
}

class RemoteApp extends StatelessWidget {
  const RemoteApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Ubuntu Remote',
    theme: ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorSchemeSeed: const Color(0xffef9546),
      scaffoldBackgroundColor: const Color(0xff10151d),
    ),
    home: const RemoteScreen(),
  );
}

class RemoteScreen extends StatefulWidget {
  const RemoteScreen({super.key});
  @override
  State<RemoteScreen> createState() => _RemoteScreenState();
}

class _RemoteScreenState extends State<RemoteScreen>
    with WidgetsBindingObserver {
  static const channel = MethodChannel('dev.owen.remote/hid');
  Map<String, dynamic> state = {};
  Timer? poll;
  final text = TextEditingController();
  int tab = 0;
  bool dragging = false, pointing = false;
  double sensitivity = 900, rx = 0, ry = 0;
  String? error;
  bool get connected => state['connected'] == true;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    refresh();
    poll = Timer.periodic(const Duration(seconds: 1), (_) => refresh());
  }

  Future<void> refresh() async {
    try {
      final value = await channel.invokeMapMethod<String, dynamic>('state');
      if (mounted) setState(() => state = value ?? {});
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<void> invoke(String method, [Map<String, dynamic>? args]) async {
    try {
      await channel.invokeMethod<void>(method, args);
      if (mounted) setState(() => error = null);
      await refresh();
    } on PlatformException catch (e) {
      if (mounted) setState(() => error = e.message ?? e.code);
    }
  }

  void mouse({int dx = 0, int dy = 0, int wheel = 0, int? buttons}) {
    invoke('mouse', {
      'dx': dx,
      'dy': dy,
      'wheel': wheel,
      'buttons': buttons ?? (dragging ? 1 : 0),
    });
  }

  Future<void> click(int button) async {
    await invoke('mouse', {'buttons': button});
    await invoke('mouse', {'buttons': 0});
  }

  void key(int usage, {int modifier = 0}) =>
      invoke('key', {'usage': usage, 'modifier': modifier});
  void point(bool enabled) {
    setState(() => pointing = enabled);
    invoke('point', {'enabled': enabled, 'sensitivity': sensitivity});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s != AppLifecycleState.resumed) {
      point(false);
      dragging = false;
    }
  }

  @override
  void dispose() {
    poll?.cancel();
    text.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  Widget action(String label, IconData icon, VoidCallback onTap) =>
      FilledButton.tonalIcon(
        onPressed: connected ? onTap : null,
        icon: Icon(icon),
        label: Text(label),
      );
  @override
  Widget build(BuildContext context) {
    final devices = (state['devices'] as List?) ?? [];
    return Scaffold(
      appBar: AppBar(
        title: const Text('Ubuntu Remote'),
        actions: [
          IconButton(
            tooltip: 'Refresh',
            onPressed: refresh,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Icon(
                            connected
                                ? Icons.bluetooth_connected
                                : Icons.bluetooth,
                            color: connected
                                ? Colors.greenAccent
                                : Colors.orangeAccent,
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              state['status'] as String? ??
                                  'Checking Bluetooth…',
                            ),
                          ),
                        ],
                      ),
                      if (!connected) ...[
                        const SizedBox(height: 10),
                        Wrap(
                          spacing: 8,
                          children: [
                            FilledButton(
                              onPressed: () => invoke('start'),
                              child: const Text('Start remote'),
                            ),
                            OutlinedButton(
                              onPressed: () => invoke('discoverable'),
                              child: const Text('Pair new host'),
                            ),
                          ],
                        ),
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 8),
                          child: Text(
                            'Pair with this phone from Ubuntu Bluetooth settings. Keep this app open while controlling Ubuntu.',
                            style: TextStyle(
                              fontSize: 12,
                              color: Colors.white60,
                            ),
                          ),
                        ),
                        if (devices.isNotEmpty)
                          DropdownButton<String>(
                            isExpanded: true,
                            hint: const Text('Select paired Ubuntu host'),
                            items: devices
                                .map(
                                  (d) => DropdownMenuItem<String>(
                                    value: d['address'] as String,
                                    child: Text(d['name'] as String),
                                  ),
                                )
                                .toList(),
                            onChanged: (address) {
                              if (address != null) {
                                invoke('connect', {'address': address});
                              }
                            },
                          ),
                      ] else
                        Align(
                          alignment: Alignment.centerRight,
                          child: TextButton(
                            onPressed: () => invoke('disconnect'),
                            child: const Text('Disconnect'),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
            if (error != null)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Text(
                  error!,
                  style: const TextStyle(color: Colors.redAccent),
                ),
              ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: LayoutBuilder(
                  builder: (context, constraints) => SingleChildScrollView(
                    child: SizedBox(
                      height: constraints.maxHeight < 470
                          ? 470
                          : constraints.maxHeight,
                      child: [touchpad(), keyboard(), presenter()][tab],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) {
          if (pointing) point(false);
          if (dragging) {
            mouse(buttons: 0);
            dragging = false;
          }
          setState(() => tab = i);
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.touch_app_outlined),
            label: 'Touchpad',
          ),
          NavigationDestination(
            icon: Icon(Icons.keyboard_outlined),
            label: 'Keyboard',
          ),
          NavigationDestination(
            icon: Icon(Icons.present_to_all),
            label: 'Presenter',
          ),
        ],
      ),
    );
  }

  Widget touchpad() => Column(
    children: [
      Expanded(
        child: Row(
          children: [
            Expanded(
              child: GestureDetector(
                onTap: connected ? () => click(1) : null,
                onDoubleTap: connected
                    ? () async {
                        await click(1);
                        await Future<void>.delayed(
                          const Duration(milliseconds: 90),
                        );
                        await click(1);
                      }
                    : null,
                onPanStart: (_) {
                  rx = 0;
                  ry = 0;
                },
                onPanUpdate: connected
                    ? (d) {
                        rx += d.delta.dx * 1.7;
                        ry += d.delta.dy * 1.7;
                        final x = rx.toInt(), y = ry.toInt();
                        rx -= x;
                        ry -= y;
                        if (x != 0 || y != 0) mouse(dx: x, dy: y);
                      }
                    : null,
                child: Container(
                  decoration: BoxDecoration(
                    color: const Color(0xff1c2633),
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: Colors.white12),
                  ),
                  child: const Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.touch_app, size: 52, color: Colors.white38),
                        SizedBox(height: 14),
                        Text('Slide to move · tap to click'),
                        SizedBox(height: 6),
                        Text(
                          'Double tap for double-click',
                          style: TextStyle(color: Colors.white38, fontSize: 12),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 10),
            GestureDetector(
              onVerticalDragUpdate: connected
                  ? (d) {
                      ry -= d.delta.dy / 12;
                      final w = ry.toInt();
                      ry -= w;
                      if (w != 0) mouse(wheel: w);
                    }
                  : null,
              child: Container(
                width: 44,
                decoration: BoxDecoration(
                  color: const Color(0xff263242),
                  borderRadius: BorderRadius.circular(18),
                ),
                child: const Center(
                  child: RotatedBox(
                    quarterTurns: 3,
                    child: Text(
                      'SCROLL',
                      style: TextStyle(letterSpacing: 3, fontSize: 11),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      Wrap(
        spacing: 10,
        runSpacing: 8,
        alignment: WrapAlignment.center,
        children: [
          action('Left', Icons.mouse, () => click(1)),
          action('Right', Icons.ads_click, () => click(2)),
          action(
            dragging ? 'Release' : 'Drag',
            dragging ? Icons.lock_open : Icons.open_with,
            () {
              setState(() => dragging = !dragging);
              mouse(buttons: dragging ? 1 : 0);
            },
          ),
        ],
      ),
    ],
  );
  Widget keyboard() => SingleChildScrollView(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          'TYPE ON UBUNTU',
          style: TextStyle(letterSpacing: 2, color: Colors.white60),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: text,
          maxLines: 4,
          maxLength: 500,
          decoration: const InputDecoration(
            border: OutlineInputBorder(),
            hintText: 'Type text to send…',
            helperText: 'US keyboard layout · ASCII text supported',
          ),
        ),
        const SizedBox(height: 10),
        FilledButton.icon(
          onPressed: connected
              ? () async {
                  await invoke('text', {'text': text.text});
                  if (error == null) text.clear();
                }
              : null,
          icon: const Icon(Icons.send),
          label: const Text('Send text'),
        ),
        const SizedBox(height: 20),
        Wrap(
          spacing: 8,
          runSpacing: 10,
          children: [
            action('Enter', Icons.keyboard_return, () => key(40)),
            action('Backspace', Icons.backspace_outlined, () => key(42)),
            action('Tab', Icons.keyboard_tab, () => key(43)),
            action('Esc', Icons.close, () => key(41)),
            action('Ctrl+C', Icons.copy, () => key(6, modifier: 1)),
            action('Ctrl+V', Icons.paste, () => key(25, modifier: 1)),
            action('Ctrl+A', Icons.select_all, () => key(4, modifier: 1)),
            action('←', Icons.arrow_back, () => key(80)),
            action('↑', Icons.arrow_upward, () => key(82)),
            action('↓', Icons.arrow_downward, () => key(81)),
            action('→', Icons.arrow_forward, () => key(79)),
          ],
        ),
      ],
    ),
  );
  Widget presenter() => Column(
    children: [
      const Text(
        'MOTION POINTER',
        style: TextStyle(letterSpacing: 2, color: Colors.white60),
      ),
      const SizedBox(height: 16),
      Expanded(
        child: Listener(
          onPointerDown: connected ? (_) => point(true) : null,
          onPointerUp: (_) => point(false),
          onPointerCancel: (_) => point(false),
          child: Container(
            width: double.infinity,
            decoration: BoxDecoration(
              color: pointing
                  ? const Color(0xff9e4b1c)
                  : const Color(0xff243143),
              borderRadius: BorderRadius.circular(28),
            ),
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.my_location,
                    size: 70,
                    color: pointing ? Colors.orangeAccent : Colors.white54,
                  ),
                  const SizedBox(height: 20),
                  Text(
                    pointing ? 'Move phone to aim' : 'Hold to aim',
                    style: const TextStyle(
                      fontSize: 25,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Release to steady the cursor',
                    style: TextStyle(color: Colors.white60),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
      Row(
        children: [
          const Text('Sensitivity'),
          Expanded(
            child: Slider(
              value: sensitivity,
              min: 300,
              max: 2200,
              onChanged: (v) => setState(() => sensitivity = v),
            ),
          ),
        ],
      ),
      Wrap(
        spacing: 10,
        runSpacing: 8,
        alignment: WrapAlignment.center,
        children: [
          action('Previous', Icons.skip_previous, () => key(75)),
          action('Next', Icons.skip_next, () => key(78)),
          action('Click', Icons.ads_click, () => click(1)),
          action('Start · F5', Icons.play_arrow, () => key(62)),
          action('Exit', Icons.close, () => key(41)),
          action('Blackout · B', Icons.visibility_off, () => key(5)),
        ],
      ),
      const SizedBox(height: 8),
      const Text(
        'Hold like a remote. Turn to aim; release to reposition.',
        style: TextStyle(color: Colors.white54, fontSize: 12),
      ),
    ],
  );
}
